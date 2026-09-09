package com.nextnotif.app

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class RelayForegroundService : Service() {

    companion object {
        const val ACTION_START = "com.nextnotif.app.START"
        const val ACTION_STOP = "com.nextnotif.app.STOP"
        const val ACTION_START_PAIRING = "com.nextnotif.app.START_PAIRING"
        const val ACTION_STOP_PAIRING = "com.nextnotif.app.STOP_PAIRING"
        const val ACTION_FORWARD_SMS = "com.nextnotif.app.FORWARD_SMS"
        const val ACTION_FORWARD_CALL = "com.nextnotif.app.FORWARD_CALL"
        private const val TAG = "RelayService"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // Single-threaded for ALL outbound event work: events must reach the relay
    // in the order they happened (RINGING before IDLE), and no sender work may
    // ever touch the main thread (legacy PhoneStateListener on API < 31
    // dispatches call events there).
    private val sendScope = CoroutineScope(
        SupervisorJob() + Executors.newSingleThreadExecutor().asCoroutineDispatcher(),
    )
    // Per-pairing sockets. Kept as a map so each pairing can reconnect
    // independently without cancelling the others.
    private val sockets = mutableMapOf<String, RelaySocket>()
    // Per-pairing uplinks for the SENDER role (one-shot POST path). Also keyed
    // by code; null when the pairing has no active sender role.
    private val uplinks = mutableMapOf<String, SenderUplink?>()
    // Per-pairing Firebase relays.
    private val firebaseRelays = mutableMapOf<String, FirebaseRelay?>()
    // Per-pairing reconnect jobs — cancelling one does not affect the others.
    private val reconnectJobs = mutableMapOf<String, kotlinx.coroutines.Job>()
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var telephonyManager: TelephonyManager? = null
    private var phoneStateListener: PhoneStateListener? = null
    private var telephonyCallback: TelephonyCallback? = null
    private val callbackExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    // True once this instance received ACTION_START. A bare ACTION_STOP can
    // boot a stopped service; stopping in that case must not tear state down
    // or kill the relay a start request about to land would establish.
    private var started = false
    private var pendingStop: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannels(this)
        startForegroundCompat()
        setupPhoneStateListener()
        setupNetworkCallback()
        pollPartnerStatuses()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                if (started) {
                    stop()
                    stopSelf()
                } else {
                    // The stop intent is what booted this service. Give a
                    // restart's start intent (stop-then-start ordering) a
                    // grace window to arrive before going away.
                    pendingStop?.cancel()
                    pendingStop = scope.launch {
                        delay(2500)
                        stopSelf()
                    }
                }
                return START_NOT_STICKY
            }
            ACTION_START_PAIRING -> {
                pendingStop?.cancel()
                started = true
                val code = intent.getStringExtra("code")
                if (code != null) {
                    val p = SessionStore.load(this).pairings.firstOrNull { it.code == code }
                    if (p != null && p.enabled) {
                        connectPairing(p)
                    }
                }
                return START_STICKY
            }
            ACTION_STOP_PAIRING -> {
                val code = intent.getStringExtra("code") ?: return START_STICKY
                if (!started) {
                    // The stop-pairing intent is what booted this service; give
                    // a pending start request a grace window to land first.
                    pendingStop?.cancel()
                    pendingStop = scope.launch {
                        delay(2500)
                        stopSelf()
                    }
                    return START_NOT_STICKY
                }
                stopPairing(code)
                // No active pairings left: a lingering foreground service is
                // useless, so tear everything down.
                if (SessionStore.load(this).pairings.none { it.enabled }) {
                    stop()
                    stopSelf()
                    return START_NOT_STICKY
                }
                return START_STICKY
            }
            ACTION_FORWARD_SMS -> {
                val sender = intent.getStringExtra("sender") ?: return START_STICKY
                val body = intent.getStringExtra("body") ?: ""
                val ts = intent.getLongExtra("ts", System.currentTimeMillis())
                forwardSms(sender, body, ts)
            }
            ACTION_FORWARD_CALL -> {
                val number = intent.getStringExtra("number") ?: return START_STICKY
                val state = intent.getStringExtra("state") ?: "UNKNOWN"
                val ts = intent.getLongExtra("ts", System.currentTimeMillis())
                val name = intent.getStringExtra("name")
                forwardCall(number, state, ts, name)
            }
            else -> {
                pendingStop?.cancel()
                started = true
                ensureConnected()
            }
        }
        return START_STICKY
    }

    private fun ensureConnected() {
        val pairings = SessionStore.load(this).pairings.filter { it.enabled }
        if (pairings.isEmpty()) return
        for (p in pairings) connectPairing(p)
    }

    /** Connect one pairing on its transport (Firebase relay or WebSocket). */
    private fun connectPairing(p: PairingInfo) {
        when (p.transport) {
            FirebaseRelay.TRANSPORT -> startFirebaseRelay(p)
            else -> connect(p) // WS (default)
        }
    }

    /** Stop one pairing's relay machinery and clear its UI state. */
    private fun stopPairing(code: String) {
        reconnectJobs.remove(code)?.cancel()
        sockets.remove(code)?.close()
        uplinks.remove(code)
        stopFirebaseRelay(code)
        AppState.clearPairingState(code)
    }

    private fun startFirebaseRelay(pairing: PairingInfo) {
        val cfg = if (pairing.fbConfig.isNullOrBlank()) {
            FirebaseConfig.DEFAULT
        } else {
            FirebaseConfig.parse(pairing.fbConfig)
        }
        if (cfg == null) {
            AppState.setConnState(pairing.code, AppState.ConnState.DISCONNECTED)
            AppState.setPairingError(pairing.code, "Firebase config is invalid (paste apiKey + databaseURL)")
            return
        }
        stopFirebaseRelay(pairing.code)
        AppState.setConnState(pairing.code, AppState.ConnState.CONNECTING)
        val relay = FirebaseRelay(
            this,
            pairing.role,
            pairing.code,
            cfg,
            onState = { s -> handleFirebaseState(pairing.code, s) },
            onIncoming = { type, data ->
                scope.launch { handleIncoming(RelaySocket.Event.Incoming(type, data), pairing.code) }
            },
        )
        firebaseRelays[pairing.code] = relay
        relay.start()
    }

    private fun handleFirebaseState(code: String, s: FirebaseRelay.State) {
        when (s) {
            FirebaseRelay.State.CONNECTED -> {
                AppState.setConnState(code, AppState.ConnState.CONNECTED)
                AppState.setPairingError(code, null)
                AppState.push("WS", "firebase connected (code $code)", code)
                Log.i(TAG, "firebase connected code=$code")
                flushFbOutbox(firebaseRelays[code] ?: return)
            }
            FirebaseRelay.State.CONNECTING -> {
                AppState.setConnState(code, AppState.ConnState.CONNECTING)
            }
            FirebaseRelay.State.DISCONNECTED -> {
                val relay = firebaseRelays[code] ?: return
                AppState.setConnState(code, AppState.ConnState.DISCONNECTED)
                val err = relay.failed
                AppState.setPairingError(code, err)
                AppState.push("WS", "firebase $code disconnected: $err", code)
                Log.w(TAG, "firebase $code: $err")
                val p = SessionStore.load(this).pairings.firstOrNull { it.code == code }
                if (p != null) scheduleReconnectFor(p)
            }
        }
    }

    private fun flushFbOutbox(relay: FirebaseRelay) {
        val queued = OutboxQueue.drain(this, relay.code)
        if (queued.isEmpty()) return
        var sent = 0
        for ((type, data) in queued) {
            if (relay.send(type, data)) sent++
        }
        AppState.push("WS", "flushed $sent queued event(s) for ${relay.code}", relay.code)
    }

    private fun stopFirebaseRelay(code: String) {
        firebaseRelays[code]?.stop()
        firebaseRelays.remove(code)
    }

    private fun connect(pairing: PairingInfo) {
        val existing = sockets[pairing.code]
        // A reconnect job can fire after a socket has already come up (late
        // close-event + scheduled retry). Churning the healthy socket causes
        // the takeover ping-pong, so never replace a socket that is open.
        if (existing?.isOpen == true) return
        existing?.close()
        sockets.remove(pairing.code)
        AppState.setConnState(pairing.code, AppState.ConnState.CONNECTING)
        var current: RelaySocket? = null
        val s = RelaySocket(this, pairing.server, pairing.role, pairing.code, pairing.deviceToken) { evt ->
            // Generation guard: a superseded socket's late events (its close
            // handshake lands after the replacement is already open) must not
            // clobber the current socket's state or schedule extra reconnects.
            // Incoming events are still delivered — the relay sends each one to
            // a single socket, so a stale delivery is the only copy.
            val superseded = sockets[pairing.code] !== current &&
                evt !is RelaySocket.Event.Incoming
            if (!superseded) {
                handleSocketEvent(pairing, current, evt)
            }
        }
        current = s
        sockets[pairing.code] = s
        s.connect()
    }

    private fun handleSocketEvent(pairing: PairingInfo, s: RelaySocket?, evt: RelaySocket.Event) {
        when (evt) {
            is RelaySocket.Event.Open -> {
                // My socket being open means THIS phone is connected; the
                // partner's state comes from the /pair/{code}/status poll.
                AppState.setConnState(pairing.code, AppState.ConnState.CONNECTED)
                AppState.setPairingError(pairing.code, null)
                AppState.push("WS", "connected as ${pairing.role} (code ${pairing.code})", pairing.code)
                Log.i(TAG, "ws open code=${pairing.code}")
                val queued = OutboxQueue.drain(this@RelayForegroundService, pairing.code)
                if (queued.isNotEmpty()) {
                    var sent = 0
                    for ((type, data) in queued) {
                        // The guard above proves s is still the current socket.
                        if (s?.send(type, data) == true) sent++
                    }
                    AppState.push("WS", "flushed $sent queued event(s) for ${pairing.code}", pairing.code)
                }
            }
            is RelaySocket.Event.AuthOk -> saveDeviceToken(pairing.code, evt.deviceToken)
            is RelaySocket.Event.Closed -> {
                AppState.setConnState(pairing.code, AppState.ConnState.DISCONNECTED)
                AppState.push("WS", "closed ${pairing.code}: ${evt.reason}", pairing.code)
                Log.w(TAG, "ws closed ${pairing.code}: ${evt.reason}")
                scheduleReconnectFor(pairing)
            }
            is RelaySocket.Event.Failure -> {
                AppState.setConnState(pairing.code, AppState.ConnState.DISCONNECTED)
                AppState.setPairingError(pairing.code, evt.error)
                AppState.push("WS", "error ${pairing.code}: ${evt.error}", pairing.code)
                Log.w(TAG, "ws failure ${pairing.code}: ${evt.error}")
                scheduleReconnectFor(pairing)
            }
            is RelaySocket.Event.Incoming -> {
                scope.launch { handleIncoming(evt, pairing.code) }
            }
        }
    }

    /** Device tokens are per pairing (the server issues one per code); store
     *  it on the pairing that earned it, not in the shared legacy field. */
    private fun saveDeviceToken(code: String, token: String) {
        val cur = SessionStore.load(this)
        val idx = cur.pairings.indexOfFirst { it.code == code }
        if (idx < 0) return
        val updated = cur.pairings.toMutableList()
        updated[idx] = updated[idx].copy(deviceToken = token)
        SessionStore.save(
            this,
            cur.copy(
                pairings = updated,
                deviceToken = if (idx == 0) token else cur.deviceToken,
            ),
        )
        Log.i(TAG, "device token stored for $code")
    }

    private fun scheduleReconnectFor(pairing: PairingInfo) {
        reconnectJobs[pairing.code]?.cancel()
        reconnectJobs[pairing.code] = scope.launch {
            val attempt = (reconnectJobs.filterValues { it.isActive }.size + 1).coerceAtMost(6)
            val delayMs = (1000L shl (attempt - 1)).coerceAtMost(15_000L)
            AppState.push("WS", "reconnecting ${pairing.code} in ${delayMs}ms (attempt $attempt)", pairing.code)
            delay(delayMs)
            connectPairing(pairing)
        }
    }

    private fun handleIncoming(evt: RelaySocket.Event.Incoming, code: String?) {
        val data = evt.data
        val number = when (evt.type) {
            "sms" -> data.optString("from")
            "call" -> data.optString("number")
            else -> ""
        }
        // Prefer this phone's own contacts (the number is usually saved here);
        // fall back to the name the sender resolved on their phone.
        val localName = if (number.isNotBlank() && number != "unknown") {
            runCatching { Contacts.lookupName(this@RelayForegroundService, number) }.getOrNull()
        } else null
        val remoteName = if (data.has("name")) data.optString("name").takeIf { it.isNotBlank() } else null
        val name = localName ?: remoteName
        if (name != null) data.put("name", name)
        AppState.pushIncoming(evt.type, data, code)
        when (evt.type) {
            "sms" -> IncomingNotifier.notifySms(this, number, data.optString("body"), name)
            "call" -> IncomingNotifier.notifyCall(this, number, data.optString("state"), name)
        }
    }

    private fun sendOrQueue(type: String, payload: JSONObject) {
        // Forward to every active sender pairing. Each pairing has its own
        // outbox queue so an offline event is replayed to ALL partners, not
        // just the first one that answers. Each pairing also gets its own
        // OUT log entry so per-pairing activity views stay accurate.
        val pairings = SessionStore.load(this).pairings.filter { it.role == Role.SENDER && it.enabled }
        for (p in pairings) {
            if (sendToPairing(p, type, payload)) {
                AppState.pushOutgoing(type, payload, p.code)
            } else {
                OutboxQueue.enqueue(this, p.code, type, payload)
                AppState.push("OUT", "queued $type (no route)", p.code)
            }
        }
        // While the network is up, also try to clear anything older that is
        // queued from an earlier offline window.
        flushOutbox()
    }

    /** Try one pairing's route now (WS uplink POST or Firebase relay). */
    private fun sendToPairing(p: PairingInfo, type: String, payload: JSONObject): Boolean {
        return if (p.transport == FirebaseRelay.TRANSPORT) {
            firebaseRelays[p.code]?.send(type, payload) ?: false
        } else {
            uplinkSend(p.code, type, payload)
        }
    }

    private fun uplinkSend(code: String, type: String, payload: JSONObject): Boolean {
        val p = SessionStore.load(this).pairings.firstOrNull { it.code == code && it.role == Role.SENDER }
            ?: return false
        val u = uplinks.getOrPut(code) {
            SenderUplink(this, p.server, p.code, p.deviceToken)
        }
        return u?.send(type, payload) ?: false
    }

    private fun flushOutbox() {
        val pairings = SessionStore.load(this).pairings.filter { it.role == Role.SENDER && it.enabled }
        if (pairings.isEmpty()) return
        var anyFailed = false
        for (p in pairings) {
            val queued = OutboxQueue.drain(this, p.code)
            if (queued.isEmpty()) continue
            val failed = mutableListOf<Pair<String, JSONObject>>()
            var sent = 0
            for ((type, data) in queued) {
                if (sendToPairing(p, type, data)) {
                    sent++
                } else {
                    failed.add(type to data)
                }
            }
            for ((type, data) in failed) OutboxQueue.enqueue(this, p.code, type, data)
            if (sent > 0) AppState.push("OUT", "flushed $sent queued event(s)", p.code)
            if (failed.isNotEmpty()) anyFailed = true
        }
        if (anyFailed) {
            AppState.push("OUT", "some queued event(s) still waiting")
            scheduleRetry()
        }
    }

    private fun scheduleRetry() {
        // Simple backoff: re-use sendScope with a fixed 30 s delay. The outbox
        // drain is idempotent, so repeated scheduling is harmless.
        val delayMs = 30_000L
        AppState.push("OUT", "retrying in ${delayMs / 1000}s")
        Log.i(TAG, "outbox retry in ${delayMs}ms")
        sendScope.launch {
            delay(delayMs)
            flushOutbox()
        }
    }

    private fun setupNetworkCallback() {
        val session = SessionStore.load(this)
        if (session.pairings.none { it.role == Role.SENDER }) return
        if (networkCallback != null) return
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // A usable network exists again: flush whatever is queued,
                // immediately instead of waiting for the next retry tick.
                sendScope.launch { flushOutbox() }
            }
        }
        runCatching { cm.registerDefaultNetworkCallback(cb) }
            .onSuccess {
                networkCallback = cb
                Log.i(TAG, "network callback registered")
            }
            .onFailure { Log.w(TAG, "network callback unavailable: ${it.message}") }
    }

    /** Poll /pair/{code}/status for every non-Firebase pairing and write
     *  the result into [AppState.partnerStates]. Runs periodically in the
     *  background while the service is alive so partner status is always
     *  up-to-date even without a live WebSocket to observe close events. */
    private fun pollPartnerStatuses() {
        scope.launch {
            while (true) {
                pollPartnerStatusesOnce()
                delay(30_000L) // every 30 s
            }
        }
    }

    private suspend fun pollPartnerStatusesOnce() {
        for (p in SessionStore.load(this@RelayForegroundService).pairings) {
            if (!p.enabled) continue
            if (p.isFirebase) continue
            val http = p.server.replaceFirst("ws://", "http://").replaceFirst("wss://", "https://")
            withContext(Dispatchers.IO) {
                runCatching {
                    val conn = URL("$http/pair/${p.code}/status").openConnection() as HttpURLConnection
                    conn.requestMethod = "GET"
                    conn.connectTimeout = 3000
                    conn.readTimeout = 3000
                    if (conn.responseCode == 200) {
                        val (partnerOnline, partnerName) =
                            partnerStatusFrom(conn.inputStream.bufferedReader().readText(), p.role)
                        AppState.setPartnerState(
                            p.code,
                            if (partnerOnline) AppState.ConnState.CONNECTED else AppState.ConnState.DISCONNECTED,
                            partnerName,
                        )
                    }
                }.onFailure { Log.w(TAG, "partner status poll failed for ${p.code}: ${it.message}") }
            }
        }
    }

    private fun teardownNetworkCallback() {
        networkCallback?.let { cb ->
            runCatching {
                (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
                    .unregisterNetworkCallback(cb)
            }
        }
        networkCallback = null
    }

    fun forwardSms(sender: String, body: String, timestamp: Long) {
        sendScope.launch {
            val name = runCatching { Contacts.lookupName(this@RelayForegroundService, sender) }.getOrNull()
            val payload = JSONObject().apply {
                put("from", sender)
                put("body", body)
                put("ts", timestamp)
                if (name != null) put("name", name)
            }
            sendOrQueue("sms", payload)
        }
    }

    fun forwardCall(number: String, state: String, timestamp: Long, name: String? = null) {
        // Runs on the single-threaded send scope: off the main thread (the
        // uplink POST is synchronous) and serialized with every other event.
        sendScope.launch {
            val payload = JSONObject().apply {
                put("number", number)
                put("state", state)
                put("ts", timestamp)
                if (name != null) put("name", name)
            }
            sendOrQueue("call", payload)
        }
    }

    private fun setupPhoneStateListener() {
        val session = SessionStore.load(this)
        // Any SENDER pairing needs the call-state listener (a phone can be
        // sender in one pairing and receiver in another).
        if (session.pairings.none { it.role == Role.SENDER }) return
        if (phoneStateListener != null || telephonyCallback != null) return
        val tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        telephonyManager = tm
        runCatching {
            if (Build.VERSION.SDK_INT >= 31) {
                val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(state: Int) {
                        handleCallState(state, null)
                    }
                }
                telephonyCallback = callback
                tm.registerTelephonyCallback(callbackExecutor, callback)
            } else {
                val listener = object : PhoneStateListener() {
                    override fun onCallStateChanged(state: Int, incomingNumber: String?) {
                        handleCallState(state, incomingNumber)
                    }
                }
                phoneStateListener = listener
                @Suppress("DEPRECATION")
                tm.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
            }
        }.onSuccess {
            Log.i(TAG, "call-state listener registered (sdk ${Build.VERSION.SDK_INT})")
        }.onFailure {
            Log.w(TAG, "call-state listener unavailable: ${it.message}")
            AppState.setError("Call listening unavailable (permissions?)")
        }
    }

    private var lastCallState: Int? = null
    private var currentCallNumber: String? = null

    private fun handleCallState(state: Int, incomingNumber: String?) {
        val previous = lastCallState
        lastCallState = state
        if (state == TelephonyManager.CALL_STATE_IDLE && previous == null) return
        val stateStr = when (state) {
            TelephonyManager.CALL_STATE_RINGING -> "RINGING"
            TelephonyManager.CALL_STATE_OFFHOOK -> "OFFHOOK"
            TelephonyManager.CALL_STATE_IDLE -> "IDLE"
            else -> "UNKNOWN"
        }
        // On API 31+ the call-state listener hides the incoming number; the
        // call-screening service (when the user enabled it) records it first.
        var number = incomingNumber?.takeIf { it.isNotBlank() }
        var screenedName: String? = null
        if (number == null && state == TelephonyManager.CALL_STATE_RINGING) {
            val screened = CallContext.takeScreened()
            number = screened?.number
            screenedName = screened?.name?.takeIf { it.isNotBlank() }
        }
        if (number != null) currentCallNumber = number
        val finalNumber: String =
            number
                ?: if (state == TelephonyManager.CALL_STATE_IDLE) currentCallNumber ?: "unknown"
                else "unknown"
        if (state == TelephonyManager.CALL_STATE_IDLE) currentCallNumber = null
        Log.i(TAG, "call $stateStr $finalNumber")
        val session = SessionStore.load(this@RelayForegroundService)
        if (session.pairings.none { it.role == Role.SENDER && it.enabled }) return
        val name = if (finalNumber != "unknown") {
            Contacts.lookupName(this@RelayForegroundService, finalNumber) ?: screenedName
        } else screenedName
        forwardCall(finalNumber, stateStr, System.currentTimeMillis(), name)
    }

    private fun teardownPhoneStateListener() {
        // Only ever set on API 31+ (where the callback API exists).
        if (Build.VERSION.SDK_INT >= 31) {
            telephonyCallback?.let { telephonyManager?.unregisterTelephonyCallback(it) }
        }
        telephonyCallback = null
        @Suppress("DEPRECATION")
        phoneStateListener?.let { telephonyManager?.listen(it, PhoneStateListener.LISTEN_NONE) }
        phoneStateListener = null
        telephonyManager = null
    }

    private fun stop() {
        for (job in reconnectJobs.values) job.cancel()
        reconnectJobs.clear()
        for ((_, s) in sockets) s.close()
        sockets.clear()
        uplinks.clear()
        for ((_, relay) in firebaseRelays) relay?.stop()
        firebaseRelays.clear()
        teardownPhoneStateListener()
        teardownNetworkCallback()
        // Wipe every per-pairing + aggregate state so the UI goes back to a
        // clean "stopped" view instead of showing stale "Connected" cards.
        AppState.clearStates()
    }

    override fun onDestroy() {
        stop()
        scope.coroutineContext[Job]?.cancel()
        sendScope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }

    private fun startForegroundCompat() {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val n: Notification = NotificationCompat.Builder(this, Notifications.CHANNEL_RELAY)
            .setContentTitle("NextNotif is on")
            .setContentText("Relaying texts and calls to your paired phones")
            .setSmallIcon(R.drawable.ic_stat_relay)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                Notifications.NOTIF_FOREGROUND,
                n,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(Notifications.NOTIF_FOREGROUND, n)
        }
    }

    object Controller {
        fun start(ctx: Context) {
            start(ctx, ACTION_START, emptyArray())
        }

        fun stop(ctx: Context) {
            val i = Intent(ctx, RelayForegroundService::class.java).setAction(ACTION_STOP)
            ctx.startService(i)
        }

        fun startPairing(ctx: Context, code: String) {
            start(ctx, ACTION_START_PAIRING, arrayOf("code" to code))
        }

        fun stopPairing(ctx: Context, code: String) {
            start(ctx, ACTION_STOP_PAIRING, arrayOf("code" to code))
        }

        fun start(ctx: Context, action: String, extras: Array<Pair<String, Any?>>) {
            val i = Intent(ctx, RelayForegroundService::class.java).setAction(action)
            for ((k, v) in extras) {
                when (v) {
                    is String -> i.putExtra(k, v)
                    is Long -> i.putExtra(k, v)
                    is Int -> i.putExtra(k, v)
                    null -> {}
                    else -> i.putExtra(k, v.toString())
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i)
            } else {
                ctx.startService(i)
            }
        }
    }
}
