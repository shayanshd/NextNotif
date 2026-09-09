package com.nextnotif.app

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Firebase transport: Firebase Anonymous Auth + Realtime Database pub/sub at
 * pairings/{code}/events.
 *
 * Anonymous auth avoids Google's Identity Toolkit API (email/password), which
 * blocks VPN/datacenter exit IPs with 403 and returns HTML that the SDK can't
 * parse. With anonymous auth the phone gets its own Firebase UID; pairing
 * isolation is enforced by the RTDB rules (read/write the path matching your
 * code) rather than per-pairing credentials.
 *
 * Outbox behaviour comes for free from RTDB offline persistence: writes made
 * while the network is down are queued by the SDK and synced on reconnect.
 */
class FirebaseRelay(
    private val context: Context,
    private val role: Role,
    val code: String,
    private val cfg: FirebaseCfg,
    private val onState: (State) -> Unit,
    private val onIncoming: (type: String, data: JSONObject) -> Unit,
) {
    enum class State { CONNECTING, CONNECTED, DISCONNECTED }

    companion object {
        private const val TAG = "FirebaseRelay"
        const val TRANSPORT = "firebase"
        private const val MAX_EVENTS = 50
        private const val AUTH_TIMEOUT_MS = 30_000L
        private val persistenceConfigured = mutableSetOf<String>()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var database: FirebaseDatabase? = null
    private var auth: FirebaseAuth? = null
    private var eventsRef: DatabaseReference? = null
    private var infoRef: DatabaseReference? = null
    private var eventsListener: ValueEventListener? = null
    private var infoListener: ValueEventListener? = null
    private var running = false
    private val seen = mutableSetOf<String>()
    private var primed = false
    private var authed = false
    private var dbOnline = false
    private var lastState: State? = null

    private val authListener = FirebaseAuth.AuthStateListener {
        if (!running) return@AuthStateListener
        val wasAuthed = authed
        authed = it.currentUser != null
        Log.d(TAG, "[${code}] authStateChanged: ${wasAuthed} -> $authed (user=${it.currentUser?.uid ?: "none"})")
        if (authed) failed = null
        emitState()
    }

    var failed: String? = null
        private set

    private fun initApp(options: FirebaseOptions): FirebaseApp {
        val existing = runCatching { FirebaseApp.getInstance() }.getOrNull()
        if (
            existing != null &&
            existing.options.databaseUrl == cfg.databaseUrl &&
            existing.options.apiKey == cfg.apiKey &&
            existing.options.applicationId == cfg.appId
        ) {
            return existing
        }
        // Config changed since last run — the cached default app points at the
        // old project/database, so replace it.
        if (existing != null) runCatching { existing.delete() }
        return FirebaseApp.initializeApp(context, options)
    }

    fun start() {
        Log.i(TAG, "[${code}] start called — role=$role dbUrl=${cfg.databaseUrl}")
        running = true
        lastState = null
        primed = false
        seen.clear()
        failed = null
        val options = FirebaseOptions.Builder()
            .setApiKey(cfg.apiKey)
            .setDatabaseUrl(cfg.databaseUrl)
            // firebase-common 21+ crashes at build() without these two.
            .setApplicationId(cfg.appId)
            .setProjectId(cfg.projectId)
            .build()
        val app = initApp(options)
        Log.i(TAG, "[${code}] FirebaseApp initialized: ${app.name}")
        val db = FirebaseDatabase.getInstance(app, cfg.databaseUrl)
        // getInstance() caches per (app, url): on a reconnect within the same
        // process the instance is already frozen, and a second
        // setPersistenceEnabled() throws. First use always precedes any
        // reference(), so configuring once per URL per process is safe.
        if (persistenceConfigured.add(cfg.databaseUrl)) {
            db.setPersistenceEnabled(true)
        }
        database = db

        // Note: getReference(...) — with the String overload present, Kotlin
        // does not map it to property-style reference(...).
        val events = db.getReference("pairings/$code/events")
        eventsRef = events
        val eventsListener = object : ValueEventListener {
            override fun onDataChange(snap: DataSnapshot) {
                if (!running) return
                if (!primed) {
                    Log.i(TAG, "[${code}] events first snapshot received, children=${snap.childrenCount}")
                    // Initial snapshot is history, not new events.
                    snap.children.forEach { it.key?.let(seen::add) }
                    primed = true
                    return
                }
                snap.children.forEach { child ->
                    val key = child.key ?: return@forEach
                    if (key in seen) return@forEach
                    seen.add(key)
                    deliver(child.value)
                }
            }

            override fun onCancelled(error: DatabaseError) {
                if (!running) return
                Log.w(TAG, "[${code}] events listener cancelled: ${error.message}")
                failed = "Relay access denied: ${error.message}"
                emitState()
            }
        }
        this.eventsListener = eventsListener
        events.addValueEventListener(eventsListener)

        val info = db.getReference(".info/connected")
        infoRef = info
        val infoListener = object : ValueEventListener {
            override fun onDataChange(snap: DataSnapshot) {
                if (!running) return
                val prev = dbOnline
                dbOnline = snap.value as? Boolean ?: false
                Log.d(TAG, "[${code}] dbOnline: $prev -> $dbOnline (authed=$authed failed=${failed ?: "none"})")
                emitState()
            }

            override fun onCancelled(error: DatabaseError) {
                if (!running) return
                Log.w(TAG, "[${code}] .info/connected listener cancelled: ${error.message}")
            }
        }
        this.infoListener = infoListener
        info.addValueEventListener(infoListener)

        val auth = FirebaseAuth.getInstance(app)
        auth.signOut()
        this.auth = auth
        auth.addAuthStateListener(authListener)
        Log.i(TAG, "[${code}] auth listener attached, starting signIn in ${AUTH_TIMEOUT_MS}ms timeout")
        scope.launch { signIn(auth) }
    }

    private suspend fun signIn(auth: FirebaseAuth) {
        Log.i(TAG, "[${code}] signIn: attempting anonymous auth (role=$role)")
        // Quick reachability probe: before spending the full auth timeout, do a
        // single TLS+HTTP probe to a Google endpoint the SDK depends on. On
        // networks that intercept HTTPS (corporate VPN, captive portal, national
        // firewall), Firebase will return HTML rather than JSON and the SDK
        // throws a confusing "Json conversion failed" exception. The probe fails
        // in <3s and gives a clear message instead of hanging.
        val reachable = runCatching {
            withTimeoutOrNull(4_000L) {
                val probeClient = OkHttpClient.Builder()
                    .connectTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                val req = Request.Builder()
                    .url("https://www.googleapis.com/identitytoken/v1")
                    .build()
                probeClient.newCall(req).execute().use { resp ->
                    // Any response (even 4xx) means TCP+TLS succeeded and we got
                    // back from Google — the endpoint host is reachable.
                    Log.i(TAG, "[${code}] google reachability probe: http=${resp.code}")
                    true
                }
            } ?: false
        }.getOrElse {
            Log.w(TAG, "[${code}] google reachability probe failed: ${it.message}")
            false
        }
        if (!reachable) {
            if (!running) return
            failed = "Can't reach Google/Firebase — check your internet or VPN settings"
            emitState()
            return
        }
        // Await the task: without this, async failures (network, blocked egress)
        // are never observed and the relay sits in CONNECTING forever.
        val completed = withTimeoutOrNull(AUTH_TIMEOUT_MS) {
            try {
                auth.signInAnonymously().await()
                true
            } catch (e: Exception) {
                if (!running) return@withTimeoutOrNull false
                Log.w(TAG, "[${code}] anonymous auth failed: ${e.message}")
                false
            }
        }
        if (completed == null) {
            if (!running) return
            Log.w(TAG, "[${code}] signIn TIMED OUT after ${AUTH_TIMEOUT_MS}ms — no response from Google")
            failed = "Couldn't reach Firebase — no internet, or Google is blocked on this network (try a VPN)"
        } else {
            Log.i(TAG, "[${code}] signIn completed (success=$completed), authed=$authed dbOnline=$dbOnline")
        }
        if (running) emitState()
    }

    /**
     * Publish an event. Returns false only if the relay is not started;
     * offline writes are queued by the RTDB SDK. Events are tagged with the
     * sending phone's role so each phone can ignore its own echoes (RTDB
     * pub/sub broadcasts writes back to the writer as well).
     */
    fun send(type: String, data: JSONObject): Boolean {
        val ref = eventsRef ?: return false
        val event = JSONObject()
            .put("type", type)
            .put("data", data)
            .put("role", role.name)
            .put("ts", System.currentTimeMillis())
        ref.push().setValue(event)
        prune(ref)
        return true
    }

    private fun prune(events: DatabaseReference) {
        events.limitToLast(MAX_EVENTS + 1).addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snap: DataSnapshot) {
                if (snap.childrenCount > MAX_EVENTS) {
                    val oldest = snap.children.firstOrNull() ?: return
                    oldest.ref.removeValue()
                }
            }

            override fun onCancelled(error: DatabaseError) {}
        })
    }

    private fun deliver(value: Any?) {
        val obj = when (value) {
            is JSONObject -> value
            // SDK versions/paths may hand back plain maps instead of JSONObjects.
            is Map<*, *> -> runCatching {
                JSONObject(value as Map<String, Any?>)
            }.getOrNull()
            else -> null
        } ?: return
        // Our own write, echoed back by RTDB — skip.
        if (obj.optString("role") == role.name) return
        val type = obj.optString("type")
        if (type.isEmpty()) return
        val data = obj.optJSONObject("data") ?: JSONObject()
        onIncoming(type, data)
    }

    private fun emitState() {
        val state = when {
            failed != null -> State.DISCONNECTED
            authed && dbOnline -> State.CONNECTED
            else -> State.CONNECTING
        }
        if (state == lastState) return
        Log.i(TAG, "[${code}] STATE CHANGE: ${lastState ?: "null"} -> $state  (authed=$authed dbOnline=$dbOnline failed=${failed ?: "none"})")
        lastState = state
        onState(state)
    }

    fun stop() {
        running = false
        scope.cancel()
        // Detach everything before signing out so the sign-out auth callback
        // cannot bounce a DISCONNECTED state back into the service (which
        // would schedule a reconnect for a relay the user just stopped).
        if (eventsRef != null && eventsListener != null) eventsRef!!.removeEventListener(eventsListener!!)
        if (infoRef != null && infoListener != null) infoRef!!.removeEventListener(infoListener!!)
        runCatching { auth?.removeAuthStateListener(authListener) }
        runCatching { database?.goOffline() }
        runCatching { auth?.signOut() }
        eventsRef = null
        infoRef = null
        eventsListener = null
        infoListener = null
        database = null
        auth = null
        authed = false
        dbOnline = false
        lastState = null
    }
}
