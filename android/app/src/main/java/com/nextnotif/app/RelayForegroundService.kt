package com.nextnotif.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import android.os.Bundle
import android.os.SystemClock
import android.net.Uri
import android.telephony.PhoneStateListener
import android.telephony.SubscriptionManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.telecom.TelecomManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
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
        const val ACTION_RELOAD = "com.nextnotif.app.RELOAD"
        const val ACTION_STOP = "com.nextnotif.app.STOP"
        const val ACTION_START_PAIRING = "com.nextnotif.app.START_PAIRING"
        const val ACTION_STOP_PAIRING = "com.nextnotif.app.STOP_PAIRING"
        const val ACTION_FORWARD_SMS = "com.nextnotif.app.FORWARD_SMS"
        const val ACTION_FORWARD_CALL = "com.nextnotif.app.FORWARD_CALL"
        const val ACTION_ANSWER_RELAY_CALL = "com.nextnotif.app.ANSWER_RELAY_CALL"
        const val ACTION_END_RELAY_CALL = "com.nextnotif.app.END_RELAY_CALL"
        const val ACTION_BEGIN_OUTGOING_CALL = "com.nextnotif.app.BEGIN_OUTGOING_CALL"
        const val ACTION_PLACE_OUTGOING_CALL = "com.nextnotif.app.PLACE_OUTGOING_CALL"
        private const val TAG = "RelayService"
        private val RECONNECT_DELAYS_MS = longArrayOf(2_000L, 5_000L, 10_000L, 30_000L, 60_000L, 120_000L)
        @Volatile private var serviceRunning = false
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
    private val answerRetryJobs = mutableMapOf<String, kotlinx.coroutines.Job>()
    private val unansweredCallJobs = mutableMapOf<String, kotlinx.coroutines.Job>()
    private val reconnectTimeoutJobs = mutableMapOf<String, kotlinx.coroutines.Job>()
    private val sessionTimeoutJobs = mutableMapOf<String, kotlinx.coroutines.Job>()
    private val mediaWatchdogJobs = mutableMapOf<String, kotlinx.coroutines.Job>()
    private val lastMediaAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    // Consecutive failures per pairing. Reset only after a successful
    // connection so a failing relay backs off instead of retrying every second.
    private val reconnectAttempts = mutableMapOf<String, Int>()
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var telephonyManager: TelephonyManager? = null
    private var phoneStateListener: PhoneStateListener? = null
    private var telephonyCallback: TelephonyCallback? = null
    private val callbackExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val callControlExecutor = java.util.concurrent.ThreadPoolExecutor(
        1, 1, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
        java.util.concurrent.ArrayBlockingQueue<Runnable>(128),
        java.util.concurrent.ThreadFactory { task -> Thread(task, "nextnotif-call-control") },
    )
    // True once this instance received ACTION_START. A bare ACTION_STOP can
    // boot a stopped service; stopping in that case must not tear state down
    // or kill the relay a start request about to land would establish.
    private var started = false
    private var preserveOnDemandStateOnDestroy = false
    private var pendingStop: Job? = null
    @Volatile private var activeCallCode: String? = null
    @Volatile private var pendingAnswerCode: String? = null
    @Volatile private var outgoingCallRequestId: String? = null
    @Volatile private var callAudioBridge: WebRtcCallAudioBridge? = null
    @Volatile private var callPeerSession: String? = null
    @Volatile private var callPeerReadySession: String? = null
    @Volatile private var stoppingCallBridge: WebRtcCallAudioBridge? = null
    private var iceLoadingSession: String? = null
    private val pendingWebRtcSignals = mutableListOf<JSONObject>()
    private val temporaryCallSockets = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val callTimeouts = LiveCallTimeoutPolicy()
    private val callMetrics = java.util.Collections.synchronizedMap(
        mutableMapOf<String, LiveCallMetricsTracker>(),
    )
    private val gatewayCapability = CachedGatewayCapability(
        probe = ProcessGatewayCapabilityProbe(),
        nowMs = SystemClock::elapsedRealtime,
    )

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        AppState.initializeMessages(this)
        serviceRunning = true
        Notifications.ensureChannels(this)
        startForegroundCompat()
        setupPhoneStateListener()
        setupNetworkCallback()
        pollPartnerStatuses()
        FcmBridge.ensureToken(this) { token ->
            sockets.values.forEach { it.updateFcmToken(token) }
        }
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
            ACTION_RELOAD -> {
                pendingStop?.cancel()
                started = true
                reloadPairings()
                if (!Controller.requiresPersistentService(this)) {
                    // A receiver-only FCM configuration needs no foreground
                    // service. Keep the Ready state established by
                    // prepareFcmOnDemand while this obsolete WS service exits.
                    preserveOnDemandStateOnDestroy = true
                    stopSelf()
                    return START_NOT_STICKY
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
            ACTION_ANSWER_RELAY_CALL -> {
                intent.getStringExtra("code")?.let(::answerRelayCall)
            }
            ACTION_END_RELAY_CALL -> {
                intent.getStringExtra("code")?.let(::endRelayCall)
            }
            ACTION_BEGIN_OUTGOING_CALL -> beginOutgoingCall(
                intent.getStringExtra("code") ?: return START_STICKY,
                intent.getStringExtra("request_id") ?: return START_STICKY,
                intent.getStringExtra("number") ?: return START_STICKY,
            )
            ACTION_PLACE_OUTGOING_CALL -> placeOutgoingCall(
                intent.getStringExtra("code") ?: return START_STICKY,
                intent.getStringExtra("request_id") ?: return START_STICKY,
                intent.getStringExtra("number") ?: return START_STICKY,
                intent.getIntExtra("subscription_id", -1).takeIf { it >= 0 },
            )
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

    /** Apply edited/removed pairings inside the existing service instance. */
    private fun reloadPairings() {
        stopCallBridge()
        val pairings = SessionStore.load(this).pairings.filter { it.enabled }
        val knownCodes = buildSet {
            addAll(pairings.map { it.code })
            addAll(sockets.keys)
            addAll(uplinks.keys)
            addAll(firebaseRelays.keys)
            addAll(reconnectJobs.keys)
        }
        knownCodes.forEach(::stopPairing)
        if (pairings.any { it.role == Role.SENDER }) {
            setupPhoneStateListener()
        } else {
            teardownPhoneStateListener()
        }
        pairings.forEach(::connectPairing)
    }

    /** Connect one pairing on its transport (Firebase relay or WebSocket). */
    private fun connectPairing(p: PairingInfo) {
        if (!p.isFirebase) SmsRelay.enqueue(this, p.code)
        // Warm the bounded root/helper cache when the user explicitly enables
        // live calls. This keeps the first RINGING event from waiting on su.
        if (p.role == Role.SENDER && p.liveCallEnabled) {
            GatewayCapabilityFeedback.set(p.code, GatewayCapability.CHECKING)
            scope.launch {
                val capability = gatewayCapability.get()
                GatewayCapabilityFeedback.set(p.code, capability)
                Log.i(TAG, "live-call gateway capability=${capability.name}")
            }
        } else {
            GatewayCapabilityFeedback.clear(p.code)
        }
        when (p.transport) {
            FirebaseRelay.TRANSPORT -> startFirebaseRelay(p)
            FcmOnDemand.TRANSPORT -> {
                if (p.role == Role.SENDER) prepareSenderUplink(p) else prepareFcmOnDemand(p)
            }
            // Live call control/audio requires a full-duplex socket on both
            // phones. The normal HTTP uplink remains a fallback for events.
            else -> connect(p)
        }
    }

    private fun prepareFcmOnDemand(pairing: PairingInfo) {
        reconnectJobs.remove(pairing.code)?.cancel()
        reconnectAttempts.remove(pairing.code)
        sockets.remove(pairing.code)?.close()
        stopFirebaseRelay(pairing.code)
        AppState.setConnState(pairing.code, AppState.ConnState.ON_DEMAND)
        AppState.setPartnerState(pairing.code, AppState.ConnState.ON_DEMAND)
        AppState.setPairingError(pairing.code, null)
        FcmOnDemand.enqueueFresh(this, pairing.code)
    }

    /**
     * A WebSocket sender is unnecessary: events already use short HTTPS POSTs
     * and the relay queues them while the receiver is away. Keep only the
     * reusable HTTP client and let the cellular radio sleep between events.
     */
    private fun prepareSenderUplink(pairing: PairingInfo) {
        reconnectJobs.remove(pairing.code)?.cancel()
        reconnectAttempts.remove(pairing.code)
        sockets.remove(pairing.code)?.close()
        uplinks.getOrPut(pairing.code) {
            SenderUplink(this, pairing.server, pairing.code, pairing.deviceToken)
        }
        AppState.setConnState(pairing.code, AppState.ConnState.CONNECTED)
        AppState.setPairingError(pairing.code, null)
        AppState.push("WS", "sender uplink ready (code ${pairing.code})", pairing.code)
        Log.i(TAG, "sender uplink ready code=${pairing.code}; no persistent socket")
    }

    /** Stop one pairing's relay machinery and clear its UI state. */
    private fun stopPairing(code: String) {
        if (callMetrics.containsKey(code) || activeCallCode == code || temporaryCallSockets.contains(code)) {
            completeLiveCall(code, "pairing_stopped")
        }
        reconnectJobs.remove(code)?.cancel()
        reconnectAttempts.remove(code)
        sockets.remove(code)?.close()
        uplinks.remove(code)
        stopFirebaseRelay(code)
        FcmOnDemand.cancel(this, code)
        GatewayCapabilityFeedback.clear(code)
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
            pairing.secret,
            onState = { s -> handleFirebaseState(pairing.code, s) },
            onPartnerState = { online, name ->
                AppState.setPartnerState(
                    pairing.code,
                    if (online) AppState.ConnState.CONNECTED else AppState.ConnState.DISCONNECTED,
                    name,
                )
            },
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
                reconnectJobs.remove(code)?.cancel()
                reconnectAttempts.remove(code)
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
                if (p != null && relay.shouldRetry) {
                    scheduleReconnectFor(p)
                } else {
                    reconnectJobs.remove(code)?.cancel()
                    Log.w(TAG, "firebase $code: automatic retry suppressed until the relay is restarted")
                }
            }
        }
    }

    private fun flushFbOutbox(relay: FirebaseRelay) {
        var sent = 0
        while (true) {
            val entry = OutboxQueue.peek(this, relay.code) ?: break
            if (!relay.send(entry.type, entry.data)) break
            if (!OutboxQueue.acknowledge(this, relay.code, entry.id)) break
            sent++
        }
        if (sent > 0) {
            AppState.push("WS", "flushed $sent queued event(s) for ${relay.code}", relay.code)
        }
    }

    private fun stopFirebaseRelay(code: String) {
        firebaseRelays[code]?.stop()
        firebaseRelays.remove(code)
    }

    private fun connect(pairing: PairingInfo) {
        AppState.setPartnerState(pairing.code, AppState.ConnState.DISCONNECTED)
        val existing = sockets[pairing.code]
        // A reconnect job can fire after a socket has already come up (late
        // close-event + scheduled retry). Churning the healthy socket causes
        // the takeover ping-pong, so never replace a socket that is open.
        if (existing?.isOpen == true) return
        existing?.close()
        sockets.remove(pairing.code)
        AppState.setConnState(pairing.code, AppState.ConnState.CONNECTING)
        var current: RelaySocket? = null
        val s = RelaySocket(
            this,
            pairing.server,
            pairing.role,
            pairing.code,
            pairing.deviceToken,
            SessionStore.fcmToken(this),
            fcmOnDemand = pairing.isFcmOnDemand,
        ) { evt ->
            // Generation guard: a superseded socket's late events (its close
            // handshake lands after the replacement is already open) must not
            // clobber the current socket's state or schedule extra reconnects.
            // Incoming events are still delivered — the relay sends each one to
            // a single socket, so a stale delivery is the only copy.
            if (LiveCallControlPolicy.acceptSocketEvent(sockets[pairing.code] === current,
                    (evt as? RelaySocket.Event.Incoming)?.type)) {
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
                reconnectJobs.remove(pairing.code)?.cancel()
                reconnectAttempts.remove(pairing.code)
                // My socket being open means THIS phone is connected; the
                // partner's state comes from the /pair/{code}/status poll.
                AppState.setConnState(pairing.code, AppState.ConnState.CONNECTED)
                AppState.setPairingError(pairing.code, null)
                AppState.push("WS", "connected as ${pairing.role} (code ${pairing.code})", pairing.code)
                Log.i(TAG, "ws open code=${pairing.code}")
                // Both roles now stay online for live calls, so refresh the
                // peer label immediately instead of waiting for the timer.
                scope.launch { pollPartnerStatusesOnce() }
                var sent = 0
                while (true) {
                    val entry = OutboxQueue.peek(this@RelayForegroundService, pairing.code) ?: break
                    // The guard above proves s is still the current socket.
                    if (s?.send(entry.type, entry.data) != true) break
                    if (!OutboxQueue.acknowledge(
                            this@RelayForegroundService,
                            pairing.code,
                            entry.id,
                        )
                    ) break
                    sent++
                }
                if (sent > 0) {
                    AppState.push("WS", "flushed $sent queued event(s) for ${pairing.code}", pairing.code)
                }
                if (activeCallCode == pairing.code) {
                    if (pairing.role == Role.RECEIVER) {
                        if (pendingAnswerCode == pairing.code) {
                            if (s?.send("call_control", JSONObject().put("action", "answer").put("transport", "webrtc")) == true) {
                                AppState.updateCall(pairing.code, AppState.CallPhase.ANSWERING)
                            }
                        } else {
                            AppState.updateCall(pairing.code, AppState.CallPhase.CONNECTING)
                            s?.send("call_control", JSONObject().put("action", "resume"))
                        }
                    } else if (lastCallState == TelephonyManager.CALL_STATE_OFFHOOK) {
                        startCallBridge(pairing)
                    }
                }
            }
            is RelaySocket.Event.AuthOk -> saveDeviceToken(pairing.code, evt.deviceToken)
            is RelaySocket.Event.Closed -> {
                if (activeCallCode == pairing.code) pauseCallForReconnect(pairing.code, evt.reason)
                AppState.setConnState(pairing.code, AppState.ConnState.DISCONNECTED)
                AppState.push("WS", "closed ${pairing.code}: ${evt.reason}", pairing.code)
                Log.w(TAG, "ws closed ${pairing.code}: ${evt.reason}")
                scheduleReconnectFor(pairing)
            }
            is RelaySocket.Event.Failure -> {
                if (activeCallCode == pairing.code) pauseCallForReconnect(pairing.code, evt.error)
                AppState.setConnState(pairing.code, AppState.ConnState.DISCONNECTED)
                AppState.setPairingError(pairing.code, evt.error)
                AppState.push("WS", "error ${pairing.code}: ${evt.error}", pairing.code)
                Log.w(TAG, "ws failure ${pairing.code}: ${evt.error}")
                scheduleReconnectFor(pairing)
            }
            is RelaySocket.Event.Incoming -> {
                if (evt.type == "call_control") {
                    // Preserve socket wire order: connected must be applied
                    // before the following SDP/ICE, even under IO contention.
                    try { callControlExecutor.execute {
                        // Recheck after queueing: this socket may have been replaced.
                        if (sockets[pairing.code] === s) handleIncoming(evt, pairing.code)
                    } }
                    catch (_: java.util.concurrent.RejectedExecutionException) {
                        synchronized(this) {
                            if (activeCallCode == pairing.code) completeLiveCall(pairing.code,
                                "control_overload", "Call signaling overloaded")
                        }
                    }
                } else scope.launch { handleIncoming(evt, pairing.code) }
            }
            is RelaySocket.Event.Binary -> {
                // WebRTC carries audio on native tracks. Never feed legacy
                // binary packets into a second, concurrent audio engine.
            }
            is RelaySocket.Event.MediaDropped -> {
                if (activeCallCode == pairing.code) callMetrics[pairing.code]?.socketDrop()
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
        val attempt = ((reconnectAttempts[pairing.code] ?: 0) + 1)
            .coerceAtMost(RECONNECT_DELAYS_MS.size)
        reconnectAttempts[pairing.code] = attempt
        reconnectJobs[pairing.code] = scope.launch {
            val delayMs = RECONNECT_DELAYS_MS[attempt - 1]
            AppState.push("WS", "reconnecting ${pairing.code} in ${delayMs}ms (attempt $attempt)", pairing.code)
            delay(delayMs)
            if (temporaryCallSockets.contains(pairing.code)) connect(pairing) else connectPairing(pairing)
        }
    }

    private fun handleIncoming(evt: RelaySocket.Event.Incoming, code: String?) {
        if (evt.type == "sms_sync" && code != null) {
            SmsRelay.enqueue(this, code)
            return
        }
        if (evt.type == "call_sync" && code != null) {
            OutgoingCallRelay.enqueue(this, code)
            return
        }
        if (evt.type == "call_control" && code != null) {
            handleCallControl(code, evt.data)
            return
        }
        // Durable replay must be checked before passive call state or teardown.
        // The shared handler owns Ringing for every transport, including FCM.
        if (!IncomingEventHandler.handle(this, evt.type, evt.data, code, evt.eventId)) return
        if (evt.type == "call" && code != null) {
            when (evt.data.optString("state")) {
                "IDLE" -> {
                    val displayedLiveCall = AppState.callRelay.value.code == code
                    if (displayedLiveCall || activeCallCode == code || pendingAnswerCode == code ||
                        temporaryCallSockets.contains(code)
                    ) completeLiveCall(code, "cellular_ended")
                }
            }
        }
    }

    private fun sendOrQueue(type: String, payload: JSONObject) {
        // Forward to every active sender pairing. Each pairing has its own
        // outbox queue so an offline event is replayed to ALL partners, not
        // just the first one that answers. Each pairing also gets its own
        // OUT log entry so per-pairing activity views stay accurate.
        val pairings = SessionStore.load(this).pairings.filter { it.role == Role.SENDER && it.enabled }
        for (p in pairings) {
            val outgoing = payloadForPairing(p, type, payload)
            if (sendToPairing(p, type, outgoing)) {
                AppState.pushOutgoing(type, outgoing, p.code)
            } else {
                OutboxQueue.enqueue(this, p.code, type, outgoing)
                AppState.push("OUT", "queued $type (no route)", p.code)
            }
        }
        // While the network is up, also try to clear anything older that is
        // queued from an earlier offline window.
        flushOutbox()
    }

    /** Attach live-call availability independently for each sender pairing. */
    private fun payloadForPairing(p: PairingInfo, type: String, payload: JSONObject): JSONObject {
        if (type != "call") return payload

        val liveAvailable = liveCallDenial(p) == null
        if (payload.optString("state") == "RINGING" && liveAvailable && p.isFcmOnDemand) {
            openTemporaryCallSocket(p)
        }
        return JSONObject(payload.toString()).put("live_call_available", liveAvailable)
    }

    /** Try one pairing's route now (WS uplink POST or Firebase relay). */
    private fun sendToPairing(p: PairingInfo, type: String, payload: JSONObject): Boolean {
        return if (p.transport == FirebaseRelay.TRANSPORT) {
            firebaseRelays[p.code]?.send(type, payload) ?: false
        } else if (p.isWs && sockets[p.code]?.send(type, payload) == true) {
            true
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
            var sent = 0
            while (true) {
                val entry = OutboxQueue.peek(this, p.code) ?: break
                if (!sendToPairing(p, entry.type, entry.data)) {
                    anyFailed = true
                    break
                }
                if (!OutboxQueue.acknowledge(this, p.code, entry.id)) {
                    anyFailed = true
                    break
                }
                sent++
            }
            if (sent > 0) AppState.push("OUT", "flushed $sent queued event(s)", p.code)
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
        if (session.pairings.none { it.enabled && (it.role == Role.SENDER || it.isWs) }) return
        if (networkCallback != null) return
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // A usable network exists again: flush whatever is queued,
                // and reconnect receiver sockets immediately instead of
                // waiting for a potentially long backoff timer.
                sendScope.launch { flushOutbox() }
                scope.launch {
                    SessionStore.load(this@RelayForegroundService).pairings
                        .filter {
                            it.enabled && it.isWs &&
                                AppState.connStates.value[it.code] == AppState.ConnState.DISCONNECTED
                        }
                        .forEach(::connectPairing)
                }
            }
        }
        runCatching { cm.registerDefaultNetworkCallback(cb) }
            .onSuccess {
                networkCallback = cb
                Log.i(TAG, "network callback registered")
            }
            .onFailure { Log.w(TAG, "network callback unavailable: ${it.message}") }
    }

    /** Poll /pair/{code}/status for every server-backed pairing and write the
     *  opposite role into [AppState.partnerStates]. FCM registrations count as
     *  on-demand readiness even though neither phone holds an idle socket. */
    private fun pollPartnerStatuses() {
        scope.launch {
            while (true) {
                pollPartnerStatusesOnce()
                delay(120_000L)
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
                        val (partnerState, partnerName) =
                            partnerStateFrom(conn.inputStream.bufferedReader().readText(), p.role)
                        AppState.setPartnerState(
                            p.code,
                            partnerState,
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
            val outgoingCode = activeCallCode.takeIf { outgoingCallRequestId != null }
            val outgoingPairing = outgoingCode?.let { code -> SessionStore.load(this@RelayForegroundService).pairings
                .firstOrNull { it.code == code && it.role == Role.SENDER && it.enabled } }
            if (outgoingPairing != null) {
                payload.put("direction", "outgoing")
                if (!sendToPairing(outgoingPairing, "call", payload)) {
                    OutboxQueue.enqueue(this@RelayForegroundService, outgoingPairing.code, "call", payload)
                }
            } else sendOrQueue("call", payload)
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
            // Do not leave a failed listener object cached: granting the
            // permission later must allow ACTION_RELOAD to register again.
            teardownPhoneStateListener()
            AppState.setError("Call listening unavailable (permissions?)")
        }
    }

    private var lastCallState: Int? = null
    private var currentCallNumber: String? = null
    private var outgoingIdleJob: Job? = null

    private fun finishOutgoingTelephonyCall() {
        val active = activeCallCode
        outgoingCallRequestId?.let { requestId -> active?.let { reportOutgoingCall(it, requestId, "ended") } }
        outgoingCallRequestId = null
        active?.let { code -> sockets[code]?.send("call_control", JSONObject().put("action", "ended")) }
        buildSet {
            active?.let(::add)
            addAll(temporaryCallSockets)
        }.forEach { completeLiveCall(it, "cellular_ended") }
    }

    @Synchronized
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
        when (state) {
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                outgoingIdleJob?.cancel()
                outgoingIdleJob = null
                outgoingCallRequestId?.let { requestId ->
                    activeCallCode?.let { code -> reportOutgoingCall(code, requestId, "connected") }
                }
                activeCallCode?.let { code ->
                    val pairing = session.pairings.firstOrNull {
                        it.code == code && it.role == Role.SENDER && it.enabled &&
                            (it.isWs || temporaryCallSockets.contains(it.code))
                    }
                    if (pairing != null) {
                        startCallBridge(pairing)
                    }
                }
                // A local answer has no selected relay pairing. If one remote
                // receiver did answer, close any other per-pairing rendezvous.
                temporaryCallSockets.toList()
                    .filter { it != activeCallCode }
                    .forEach { completeLiveCall(it, "not_selected") }
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                // Some Samsung builds emit a transient IDLE between placeCall
                // and OFFHOOK. Delay teardown so the receiver does not show
                // “Call ended” while the cellular call is still starting.
                val requestId = outgoingCallRequestId
                if (requestId != null && previous != TelephonyManager.CALL_STATE_OFFHOOK) {
                    outgoingIdleJob?.cancel()
                    outgoingIdleJob = scope.launch {
                        delay(2_500L)
                        if (lastCallState == TelephonyManager.CALL_STATE_IDLE && outgoingCallRequestId == requestId) {
                            finishOutgoingTelephonyCall()
                        }
                    }
                    return
                }
                outgoingIdleJob = null
                finishOutgoingTelephonyCall()
            }
        }
    }

    @Synchronized
    private fun answerRelayCall(code: String) {
        val current = AppState.callRelay.value
        if (!RelayCallUiPolicy.canBeginAnswer(activeCallCode, current.phase, current.code, code)) return
        val pairing = SessionStore.load(this).pairings.firstOrNull {
            it.code == code && it.role == Role.RECEIVER && it.enabled &&
                (it.isWs || it.isFcmOnDemand)
        } ?: run {
            AppState.setError("Call relay requires an enabled FCM or WebSocket receiver pairing")
            AppState.finishCall(code, "Call relay is not available for this pairing")
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            AppState.finishCall(code, "Allow Microphone on the receiver before answering a relayed call")
            return
        }
        beginCallTracking(code)
        activeCallCode = code
        pendingAnswerCode = code
        AppState.updateCall(code, AppState.CallPhase.ANSWERING)
        updateCallForeground()
        if (sockets[code]?.send("call_control", JSONObject().put("action", "answer").put("transport", "webrtc")) == true) {
            AppState.push("CALL", "answer requested on ${pairing.displayName}", code)
        } else if (pairing.isFcmOnDemand) {
            openTemporaryCallSocket(pairing)
        } else {
            AppState.updateCall(code, AppState.CallPhase.RECONNECTING, "Waiting for the sender phone")
            scheduleReconnectFor(pairing)
        }
        startAnswerRetry(pairing)
    }

    private fun endRelayCall(code: String) {
        sockets[code]?.send("call_control", JSONObject().put("action", "end").apply {
            callPeerSession?.let { put("session_id", it) }
        })
        completeLiveCall(code, "local_hangup")
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission") // guarded by canControlPhoneCalls() before Telecom calls
    @Synchronized
    private fun handleCallControl(code: String, data: JSONObject) {
        val pairing = SessionStore.load(this).pairings.firstOrNull { it.code == code } ?: return
        val action = data.optString("action")
        // A delayed generation-specific error/end cannot terminate its replacement.
        if (!LiveCallControlPolicy.acceptSessionControl(action,
                if (data.has("session_id")) data.optString("session_id") else null, callPeerSession)) return
        if (pairing.role == Role.SENDER &&
            !LiveCallControlPolicy.acceptSenderControl(action, code, activeCallCode)) return
        if (pairing.role == Role.SENDER && action == "answer" && data.optString("transport") != "webrtc") {
            sockets[code]?.send("call_control", JSONObject().put("action", "error")
                .put("message", "Update both phones to use WebRTC calls"))
            return
        }
        if (action == "webrtc_signal") {
            val session = callPeerSession ?: return
            if (activeCallCode != code) return
            val remote = if (pairing.role == Role.SENDER) Role.RECEIVER else Role.SENDER
            if (WebRtcSignal.parse(data, session, remote) == null) return
            if (callPeerReadySession != session) {
                if (pendingWebRtcSignals.size >= 128) {
                    completeLiveCall(code, "signaling_overflow", "Too many pending call signals")
                } else pendingWebRtcSignals.add(JSONObject(data.toString()))
                return
            }
            if (callAudioBridge?.receive(data, remote) != true) {
                completeLiveCall(code, "signaling_not_ready", "Call audio was not ready for negotiation")
            }
            return
        }
        if (pairing.role == Role.SENDER && action in setOf("answer", "resume", "end")) {
            liveCallDenial(pairing)?.let { message ->
                sockets[code]?.send(
                    "call_control",
                    JSONObject().put("action", "error").put("message", message),
                )
                AppState.push("CALL", "rejected $action: $message", code)
                return
            }
        }
        when (action) {
            "answer" -> if (pairing.role == Role.SENDER && activeCallCode == code &&
                lastCallState == TelephonyManager.CALL_STATE_OFFHOOK
            ) {
                callPeerReadySession?.let { session ->
                    sockets[code]?.send("call_control", JSONObject().put("action", "connected")
                        .put("transport", "webrtc").put("session_id", session))
                }
            } else if (pairing.role == Role.SENDER && lastCallState == TelephonyManager.CALL_STATE_RINGING) {
                if (!canControlPhoneCalls()) {
                    sockets[code]?.send(
                        "call_control",
                        JSONObject().put("action", "error")
                            .put("message", "Allow phone answering on the sender to use live calls"),
                    )
                    return
                }
                beginCallTracking(code)
                unansweredCallJobs.remove(code)?.cancel()
                activeCallCode = code
                val telecom = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
                runCatching { telecom.acceptRingingCall() }
                    .onFailure {
                        sockets[code]?.send(
                            "call_control",
                                JSONObject().put("action", "error").put("message", it.message ?: "answer failed"),
                        )
                        completeLiveCall(code, "answer_failed", it.message ?: "Could not answer on sender")
                    }
            }
            "connected" -> if (pairing.role == Role.RECEIVER && activeCallCode == code) {
                val session = data.optString("session_id")
                if (data.optString("transport") != "webrtc" ||
                    runCatching { java.util.UUID.fromString(session).toString() == session }.getOrDefault(false).not()
                ) {
                    completeLiveCall(code, "transport_mismatch", "Update both phones to use WebRTC calls")
                    return
                }
                pendingAnswerCode = null
                answerRetryJobs.remove(code)?.cancel()
                if (callPeerSession == session) return
                retireCallPeer()
                callPeerSession = session
                AppState.updateCall(code, AppState.CallPhase.CONNECTING)
                startCallBridge(pairing)
            }
            "resume" -> if (pairing.role == Role.SENDER && lastCallState == TelephonyManager.CALL_STATE_OFFHOOK) {
                activeCallCode = code
                retireCallPeer()
                startCallBridge(pairing)
            }
            "end" -> if (pairing.role == Role.SENDER) {
                if (Build.VERSION.SDK_INT >= 28) {
                    if (!canControlPhoneCalls()) {
                        sockets[code]?.send(
                            "call_control",
                            JSONObject().put("action", "error")
                                .put("message", "Allow phone answering on the sender to hang up remotely"),
                        )
                        return
                    }
                    runCatching { (getSystemService(Context.TELECOM_SERVICE) as TelecomManager).endCall() }
                } else {
                    // TelecomManager.endCall() is API 28+. The rooted Android
                    // 8 gateway can still issue the standard end-call key.
                    val hungUp = runCatching {
                        BoundedCommand.successful(ProcessBuilder("su", "-c", "input keyevent 6").start(), 5_000)
                    }.getOrDefault(false)
                    if (!hungUp) {
                        val message = "Could not confirm remote hang-up. End the call on the sender phone."
                        sockets[code]?.send("call_control", JSONObject().put("action", "error").put("message", message))
                        completeLiveCall(code, "remote_hangup_failed", message)
                        return
                    }
                }
                completeLiveCall(code, "remote_hangup")
            }
            "ended" -> {
                completeLiveCall(code, "remote_ended")
            }
            "error" -> {
                val message = data.optString("message", "unknown error")
                AppState.setError("Call relay: $message")
                completeLiveCall(code, "remote_error", message)
            }
        }
    }

    private fun canControlPhoneCalls(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ANSWER_PHONE_CALLS) ==
            PackageManager.PERMISSION_GRANTED

    @Synchronized
    private fun beginOutgoingCall(code: String, requestId: String, number: String) {
        if (activeCallCode != null) return
        val pairing = SessionStore.load(this).pairings.firstOrNull {
            it.code == code && it.role == Role.RECEIVER && it.enabled && !it.isFirebase
        } ?: return
        activeCallCode = code
        outgoingCallRequestId = requestId
        AppState.updateCall(code, AppState.CallPhase.CONNECTING, number = number)
        openTemporaryCallSocket(pairing)
    }

    @Synchronized
    private fun placeOutgoingCall(code: String, requestId: String, number: String, subscriptionId: Int?) {
        val pairing = SessionStore.load(this).pairings.firstOrNull {
            it.code == code && it.role == Role.SENDER && it.enabled && !it.isFirebase
        } ?: return
        if (outgoingCallRequestId == requestId) return
        if (activeCallCode != null) {
            reportOutgoingCall(code, requestId, "failed", "Sender phone is already handling another relayed call")
            return
        }
        val denial = liveCallDenial(pairing)
            ?: if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED)
                "Allow Phone permission on the sender to place calls" else null
        if (denial != null) {
            reportOutgoingCall(code, requestId, "failed", denial)
            return
        }
        val subscription = selectSmsSubscription(subscriptionId,
            getSystemService(SubscriptionManager::class.java).activeSubscriptionInfoList.orEmpty().map { it.subscriptionId },
            SubscriptionManager.getDefaultVoiceSubscriptionId())
        if (subscription == null) {
            reportOutgoingCall(code, requestId, "failed", "Selected SIM is unavailable on the sender")
            return
        }
        if (lastCallState != null && lastCallState != TelephonyManager.CALL_STATE_IDLE) {
            reportOutgoingCall(code, requestId, "failed", "Sender phone is already in a call")
            return
        }
        activeCallCode = code
        outgoingCallRequestId = requestId
        currentCallNumber = number
        openTemporaryCallSocket(pairing)
        val telecom = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        val accounts = telecom.callCapablePhoneAccounts
        val selectedInfo = getSystemService(SubscriptionManager::class.java).activeSubscriptionInfoList.orEmpty()
            .firstOrNull { it.subscriptionId == subscription }
        val handle = accounts.singleOrNull {
            phoneAccountMatchesSubscription(it.id, subscription, selectedInfo?.iccId)
        }
        if (handle == null) {
            reportOutgoingCall(code, requestId, "failed", "Could not match the selected SIM to a calling account")
            completeLiveCall(code, "dial_failed")
            return
        }
        val extras = Bundle().putParcelableCompat(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle)
        runCatching { telecom.placeCall(Uri.fromParts("tel", number, null), extras) }
            .onSuccess { reportOutgoingCall(code, requestId, "dialing") }
            .onFailure {
                reportOutgoingCall(code, requestId, "failed", it.message ?: "Sender could not place the call")
                completeLiveCall(code, "dial_failed")
            }
    }

    private fun Bundle.putParcelableCompat(key: String, value: android.os.Parcelable): Bundle = apply { putParcelable(key, value) }

    private fun reportOutgoingCall(code: String, requestId: String, status: String, detail: String = "") {
        val pairing = SessionStore.load(this).pairings.firstOrNull { it.code == code && it.role == Role.SENDER } ?: return
        scope.launch { runCatching { OutgoingCallRelay.report(this@RelayForegroundService, pairing, requestId, status, detail) } }
    }

    private fun liveCallDenial(pairing: PairingInfo): String? {
        if (pairing.role != Role.SENDER || !pairing.enabled || !pairing.liveCallEnabled) {
            return "Live call relay is not enabled on the sender phone"
        }
        if (!PermissionPolicy.liveCallPermissionsReady(
                answerGranted = canControlPhoneCalls(),
                microphoneGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED,
            )
        ) {
            return "Finish live call setup: allow Phone and Microphone on the sender"
        }
        val capability = gatewayCapability.get()
        GatewayCapabilityFeedback.set(pairing.code, capability)
        return capability.takeUnless { it == GatewayCapability.AVAILABLE }
            ?.userMessage()
    }

    @Synchronized
    private fun startCallBridge(pairing: PairingInfo, provisionedIce: List<org.webrtc.PeerConnection.IceServer>? = null) {
        if (callAudioBridge != null || activeCallCode != pairing.code) return
        if (pairing.role == Role.SENDER) {
            liveCallDenial(pairing)?.let { message ->
                sockets[pairing.code]?.send(
                    "call_control",
                    JSONObject().put("action", "error").put("message", message),
                )
                AppState.setError(message)
                stopCallBridge()
                return
            }
        }
        val session = callPeerSession ?: if (pairing.role == Role.SENDER) {
            WebRtcSignal.newSessionId().also { callPeerSession = it }
        } else return
        val previous = stoppingCallBridge
        if (previous != null) {
            scope.launch {
                if (!previous.awaitStopped(callTimeouts.reconnectMs)) {
                    synchronized(this@RelayForegroundService) {
                        if (callPeerSession == session) completeLiveCall(pairing.code,
                            "audio_stop_timeout", "Previous call audio is still stopping")
                    }
                    return@launch
                }
                synchronized(this@RelayForegroundService) {
                    if (stoppingCallBridge === previous) stoppingCallBridge = null
                    if (callPeerSession == session && activeCallCode == pairing.code) startCallBridge(pairing, provisionedIce)
                }
            }
            return
        }
        if (provisionedIce == null) {
            if (iceLoadingSession == session) return
            iceLoadingSession = session
            // Includes credential fetching. Peer replacement never resets this deadline.
            if (!reconnectTimeoutJobs.containsKey(pairing.code)) reconnectTimeoutJobs[pairing.code] = scope.launch {
                delay(callTimeouts.rendezvousMs)
                synchronized(this@RelayForegroundService) {
                    if (activeCallCode == pairing.code) completeLiveCall(pairing.code,
                        "negotiation_timeout", "Could not connect WebRTC call audio")
                }
            }
            promoteCallForeground()
            AppState.updateCall(pairing.code, AppState.CallPhase.CONNECTING, "Preparing secure call audio")
            scope.launch {
                val result = runCatching {
                    val current = SessionStore.load(this@RelayForegroundService).pairings.first {
                        it.code == pairing.code && it.role == pairing.role && it.enabled
                    }
                    WebRtcIceClient.fetch(current)
                }
                synchronized(this@RelayForegroundService) {
                    if (callPeerSession != session || activeCallCode != pairing.code || iceLoadingSession != session) return@synchronized
                    iceLoadingSession = null
                    result.fold(
                        onSuccess = { startCallBridge(pairing, it) },
                        onFailure = {
                            sockets[pairing.code]?.send("call_control", JSONObject().put("action", "error")
                                .put("session_id", session).put("message", "Could not prepare TURN call audio"))
                            completeLiveCall(pairing.code, "turn_unavailable", "Could not prepare TURN call audio")
                        },
                    )
                }
            }
            return
        }
        val bridge = WebRtcCallAudioBridge(
            context = this,
            role = pairing.role,
            sessionId = session,
            capability = if (pairing.role == Role.SENDER) gatewayCapability.get() else GatewayCapability.ROOT_UNAVAILABLE,
            iceServers = provisionedIce,
            sendSignal = { signal -> synchronized(this@RelayForegroundService) {
                callPeerSession == session && activeCallCode == pairing.code &&
                    sockets[pairing.code]?.send("call_control", signal.put("action", "webrtc_signal")) == true
            } },
            onReady = { synchronized(this@RelayForegroundService) {
                if (callPeerSession == session && activeCallCode == pairing.code) {
                    callPeerReadySession = session
                    val remote = if (pairing.role == Role.SENDER) Role.RECEIVER else Role.SENDER
                    val queued = pendingWebRtcSignals.toList()
                    pendingWebRtcSignals.clear()
                    for (signal in queued) {
                        if (callAudioBridge?.receive(signal, remote) != true) {
                            completeLiveCall(pairing.code, "signaling_not_ready", "Could not negotiate call audio")
                            break
                        }
                    }
                }
                if (callPeerSession == session && activeCallCode == pairing.code && pairing.role == Role.SENDER) {
                    callPeerReadySession = session
                    if (sockets[pairing.code]?.send("call_control", JSONObject().put("action", "connected")
                            .put("transport", "webrtc").put("session_id", session)) != true) {
                        pauseCallForReconnect(pairing.code, "Call signaling disconnected")
                    }
                }
            } },
            onState = { state -> synchronized(this@RelayForegroundService) {
                if (callPeerSession == session && activeCallCode == pairing.code) {
                    when (state) {
                        org.webrtc.PeerConnection.PeerConnectionState.CONNECTED -> {
                            callMetrics[pairing.code]?.connected()
                            reconnectTimeoutJobs.remove(pairing.code)?.cancel()
                            startWebRtcMediaWatchdog(pairing.code, session)
                            AppState.updateCall(pairing.code, AppState.CallPhase.ACTIVE)
                            updateCallForeground()
                        }
                        org.webrtc.PeerConnection.PeerConnectionState.DISCONNECTED,
                        org.webrtc.PeerConnection.PeerConnectionState.FAILED -> {
                            pauseCallForReconnect(pairing.code, "WebRTC connection interrupted")
                            if (pairing.role == Role.SENDER) startCallBridge(pairing)
                            else sockets[pairing.code]?.send("call_control", JSONObject().put("action", "resume"))
                        }
                        else -> Unit
                    }
                }
            } },
            onError = { detail ->
                synchronized(this@RelayForegroundService) {
                    if (callPeerSession == session && activeCallCode == pairing.code) {
                        sockets[pairing.code]?.send("call_control", JSONObject().put("action", "error")
                            .put("message", detail).put("session_id", session))
                        completeLiveCall(pairing.code, "webrtc_error", detail)
                    }
                }
            },
        )
        callAudioBridge = bridge
        // Android 14 enforces the microphone foreground-service type before
        // AudioRecord is opened. This method is reached from the receiver's
        // explicit notification action, satisfying the while-in-use grant.
        promoteCallForeground()
        if (bridge.start()) {
            if (!reconnectTimeoutJobs.containsKey(pairing.code)) reconnectTimeoutJobs[pairing.code] = scope.launch {
                delay(callTimeouts.rendezvousMs)
                synchronized(this@RelayForegroundService) {
                    // The deadline belongs to the call attempt, not a peer.
                    // Replacing a failed peer must not disable or reset it.
                    if (activeCallCode == pairing.code) completeLiveCall(pairing.code,
                        "negotiation_timeout", "Could not connect WebRTC call audio")
                }
            }
            AppState.push("CALL", "WebRTC audio negotiating as ${pairing.role}", pairing.code)
            AppState.updateCall(pairing.code, AppState.CallPhase.CONNECTING)
            updateCallForeground()
        } else {
            completeLiveCall(pairing.code, "audio_start_failed", "Could not start live call audio")
        }
    }

    private fun beginCallTracking(code: String) {
        if (callMetrics.containsKey(code)) return
        callMetrics[code] = LiveCallMetricsTracker(SystemClock.elapsedRealtime(), SystemClock::elapsedRealtime)
        sessionTimeoutJobs[code] = scope.launch {
            delay(callTimeouts.sessionMs)
            completeLiveCall(code, "session_timeout", "The live-call session expired")
        }
    }

    @Synchronized
    private fun startWebRtcMediaWatchdog(code: String, session: String) {
        if (mediaWatchdogJobs.containsKey(code)) return
        val health = WebRtcMediaHealth(SystemClock.elapsedRealtime())
        var telemetryWarningShown = false
        var mediaObserved = false
        var lastRtpDiagnosticAt = 0L
        mediaWatchdogJobs[code] = scope.launch {
            while (true) {
                delay(2_000)
                synchronized(this@RelayForegroundService) {
                    if (callPeerSession != session || activeCallCode != code) return@launch
                    if (!telemetryWarningShown && health.telemetryUnavailable(SystemClock.elapsedRealtime())) {
                        telemetryWarningShown = true
                        AppState.push("CALL", "Native audio health statistics are unavailable", code)
                    }
                    callAudioBridge?.requestReceivedPackets { count ->
                        synchronized(this@RelayForegroundService) {
                            if (callPeerSession == session) {
                                val now = SystemClock.elapsedRealtime()
                                health.observe(now, count)
                                if (BuildConfig.DEBUG && now - lastRtpDiagnosticAt >= 5_000L) {
                                    lastRtpDiagnosticAt = now
                                    Log.i("WebRtcMediaHealth", "path=inbound_rtp packets=${count ?: "unavailable"}")
                                }
                                if (!mediaObserved && count != null && count > 0) {
                                    mediaObserved = true
                                    Log.i(TAG, "WebRTC inbound RTP observed")
                                    AppState.push("CALL", "WebRTC receiving audio packets", code)
                                }
                                // Confirm against a fresh result, not the previous
                                // poll. Missing telemetry is not evidence of silence.
                                if (count != null && health.stalled(now)) {
                                    sockets[code]?.send("call_control", JSONObject().put("action", "error")
                                        .put("session_id", session).put("message", "Call audio stopped arriving"))
                                    completeLiveCall(code, "rtp_stall", "Call audio stopped arriving")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Synchronized
    private fun completeLiveCall(code: String, reason: String, error: String? = null) {
        unansweredCallJobs.remove(code)?.cancel()
        reconnectTimeoutJobs.remove(code)?.cancel()
        sessionTimeoutJobs.remove(code)?.cancel()
        mediaWatchdogJobs.remove(code)?.cancel()
        lastMediaAt.remove(code)
        answerRetryJobs.remove(code)?.cancel()
        if (activeCallCode == code) {
            stopCallBridge()
            outgoingCallRequestId?.let { OutgoingCallRequestStore.clearIfCurrent(this, code, it) }
            outgoingCallRequestId = null
        }
        callMetrics.remove(code)?.let { tracker ->
            val summary = tracker.finish(reason).logLine()
            Log.i(TAG, "call metrics code=$code $summary")
            AppState.push("CALL", "call summary $summary", code)
        }
        if (AppState.callRelay.value.code == code) AppState.finishCall(code, error)
        closeTemporaryCallSocket(code)
        if (!Controller.requiresPersistentService(this) && temporaryCallSockets.isEmpty()) {
            preserveOnDemandStateOnDestroy = true
            stopSelf()
        }
    }

    @Synchronized
    private fun pauseCallForReconnect(code: String, detail: String?) {
        mediaWatchdogJobs.remove(code)?.cancel()
        lastMediaAt.remove(code)
        retireCallPeer()
        if (!reconnectTimeoutJobs.containsKey(code)) {
            callMetrics[code]?.reconnected()
            reconnectTimeoutJobs[code] = scope.launch {
                delay(callTimeouts.reconnectMs)
                completeLiveCall(code, "reconnect_timeout", "Could not reconnect to the other phone")
            }
        }
        AppState.updateCall(code, AppState.CallPhase.RECONNECTING, detail)
        updateCallForeground()
    }

    @Synchronized
    private fun stopCallBridge() {
        retireCallPeer()
        activeCallCode = null
        pendingAnswerCode = null
        answerRetryJobs.values.forEach { it.cancel() }
        answerRetryJobs.clear()
        if (started) startForegroundCompat()
    }

    @Synchronized
    private fun retireCallPeer() {
        iceLoadingSession = null
        pendingWebRtcSignals.clear()
        callPeerSession = null
        callPeerReadySession = null
        val bridge = callAudioBridge
        callAudioBridge = null
        if (bridge != null) {
            stoppingCallBridge = bridge
            bridge.close()
        }
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

    private fun stop(clearUiState: Boolean = true) {
        stopCallBridge()
        unansweredCallJobs.values.forEach { it.cancel() }
        unansweredCallJobs.clear()
        reconnectTimeoutJobs.values.forEach { it.cancel() }
        reconnectTimeoutJobs.clear()
        sessionTimeoutJobs.values.forEach { it.cancel() }
        sessionTimeoutJobs.clear()
        mediaWatchdogJobs.values.forEach { it.cancel() }
        mediaWatchdogJobs.clear()
        lastMediaAt.clear()
        callMetrics.clear()
        for (job in reconnectJobs.values) job.cancel()
        reconnectJobs.clear()
        answerRetryJobs.values.forEach { it.cancel() }
        answerRetryJobs.clear()
        reconnectAttempts.clear()
        temporaryCallSockets.clear()
        for ((_, s) in sockets) s.close()
        sockets.clear()
        uplinks.clear()
        for ((_, relay) in firebaseRelays) relay?.stop()
        firebaseRelays.clear()
        teardownPhoneStateListener()
        teardownNetworkCallback()
        // Wipe every per-pairing + aggregate state so the UI goes back to a
        // clean "stopped" view instead of showing stale "Connected" cards.
        if (clearUiState) AppState.clearStates()
    }

    override fun onDestroy() {
        serviceRunning = false
        callControlExecutor.shutdownNow()
        stop(clearUiState = !preserveOnDemandStateOnDestroy)
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

    private fun promoteCallForeground() {
        updateCallForeground()
    }

    private fun updateCallForeground() {
        val call = AppState.callRelay.value
        val code = call.code ?: activeCallCode ?: return
        val pi = PendingIntent.getActivity(
            this,
            20_001,
            RelayCallActivity.createIntent(this, code, call.number, call.name, answer = false),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val status = when (call.phase) {
            AppState.CallPhase.ANSWERING -> "Answering on the sender phone…"
            AppState.CallPhase.CONNECTING -> "Connecting call audio…"
            AppState.CallPhase.RECONNECTING -> "Connection interrupted — reconnecting…"
            AppState.CallPhase.ACTIVE -> "Call connected"
            else -> "Live call relay"
        }
        val hangup = PendingIntent.getService(
            this,
            20_002,
            Intent(this, RelayForegroundService::class.java)
                .setAction(ACTION_END_RELAY_CALL)
                .putExtra("code", code),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(this, Notifications.CHANNEL_RELAY)
            .setContentTitle(call.callerLabel)
            .setContentText(status)
            .setSmallIcon(R.drawable.ic_stat_relay)
            .setContentIntent(pi)
            .setOngoing(true)
            .addAction(0, "Hang up", hangup)
            .build()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            startForeground(Notifications.NOTIF_FOREGROUND, n)
            return
        }
        var types = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            types = types or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        startForeground(Notifications.NOTIF_FOREGROUND, n, types)
    }

    private fun openTemporaryCallSocket(pairing: PairingInfo) {
        if (!pairing.isFcmOnDemand) return
        if (pairing.role == Role.SENDER && liveCallDenial(pairing) != null) return
        temporaryCallSockets.add(pairing.code)
        beginCallTracking(pairing.code)
        if (pairing.role == Role.SENDER && !unansweredCallJobs.containsKey(pairing.code)) {
            unansweredCallJobs[pairing.code] = scope.launch {
                delay(callTimeouts.unansweredMs)
                if (activeCallCode != pairing.code) completeLiveCall(pairing.code, "unanswered_timeout")
            }
        }
        if (sockets[pairing.code]?.isOpen != true) connect(pairing)
        AppState.push("CALL", "temporary call connection opening", pairing.code)
    }

    private fun startAnswerRetry(pairing: PairingInfo) {
        answerRetryJobs.remove(pairing.code)?.cancel()
        answerRetryJobs[pairing.code] = scope.launch {
            repeat((callTimeouts.rendezvousMs / 1_000L).toInt()) { attempt ->
                if (pendingAnswerCode != pairing.code || activeCallCode != pairing.code) return@launch
                if (sockets[pairing.code]?.send(
                        "call_control",
                        JSONObject().put("action", "answer").put("transport", "webrtc"),
                    ) == true && attempt > 0
                ) {
                    AppState.push("CALL", "answer rendezvous retry ${attempt + 1}", pairing.code)
                }
                delay(1_000L)
            }
            if (pendingAnswerCode == pairing.code) {
                completeLiveCall(pairing.code, "rendezvous_timeout", "The sender phone did not join the call")
            }
        }
    }

    private fun closeTemporaryCallSocket(code: String) {
        if (!temporaryCallSockets.remove(code)) return
        reconnectJobs.remove(code)?.cancel()
        answerRetryJobs.remove(code)?.cancel()
        reconnectAttempts.remove(code)
        sockets.remove(code)?.close()
        val pairing = SessionStore.load(this).pairings.firstOrNull { it.code == code }
        if (pairing?.role == Role.RECEIVER) {
            AppState.setConnState(code, AppState.ConnState.ON_DEMAND)
            FcmOnDemand.enqueueFresh(this, code)
        } else if (pairing?.role == Role.SENDER) {
            AppState.setConnState(code, AppState.ConnState.CONNECTED)
        }
        AppState.push("CALL", "returned to FCM on demand", code)
    }

    object Controller {
        fun start(ctx: Context) {
            if (!SessionStore.load(ctx).relayEnabled) return
            FcmOnDemand.enqueueAll(ctx)
            if (!requiresPersistentService(ctx)) return
            start(ctx, ACTION_START, emptyArray())
        }

        fun reload(ctx: Context) {
            if (!SessionStore.load(ctx).relayEnabled) {
                stop(ctx)
                return
            }
            FcmOnDemand.enqueueAll(ctx)
            if (!serviceRunning) {
                start(ctx)
                return
            }
            start(ctx, ACTION_RELOAD, emptyArray())
        }

        fun stop(ctx: Context) {
            if (!serviceRunning) {
                SessionStore.load(ctx).pairings
                    .filter { it.isFcmOnDemand }
                    .forEach { FcmOnDemand.cancel(ctx, it.code) }
                AppState.clearStates()
                return
            }
            val i = Intent(ctx, RelayForegroundService::class.java).setAction(ACTION_STOP)
            ctx.startService(i)
        }

        fun startPairing(ctx: Context, code: String) {
            if (!SessionStore.load(ctx).relayEnabled) return
            val pairing = SessionStore.load(ctx).pairings.firstOrNull { it.code == code }
            if (pairing?.role == Role.RECEIVER && pairing.isFcmOnDemand) {
                FcmOnDemand.enqueueFresh(ctx, code)
                return
            }
            start(ctx, ACTION_START_PAIRING, arrayOf("code" to code))
        }

        fun stopPairing(ctx: Context, code: String) {
            FcmOnDemand.cancel(ctx, code)
            if (!serviceRunning) {
                AppState.clearPairingState(code)
                return
            }
            start(ctx, ACTION_STOP_PAIRING, arrayOf("code" to code))
        }

        @Synchronized
        fun answerCall(ctx: Context, code: String, number: String?, name: String?, offeredAt: Long? = null) {
            val pairing = SessionStore.load(ctx).pairings.firstOrNull { it.code == code } ?: return
            if (pairing.role != Role.RECEIVER || !pairing.enabled ||
                !(pairing.isWs || pairing.isFcmOnDemand)) return
            val current = AppState.callRelay.value
            if (!RelayCallUiPolicy.canRequestAnswer(current.phase, current.code, code)) return
            val sourceTime = if (current.code == code) current.offeredAt else offeredAt
            if (!CallEventFreshness.permitsInteraction(sourceTime, System.currentTimeMillis())) {
                if (current.code == code) AppState.finishCall(code, ctx.getString(R.string.call_offer_expired))
                AppState.setError(ctx.getString(R.string.call_offer_expired))
                return
            }
            if (current.phase == AppState.CallPhase.IDLE) AppState.setIncomingCall(code, number, name, sourceTime)
            // Claim the request synchronously so rapid taps cannot enqueue another
            // answer while Android is still starting the foreground service.
            AppState.updateCall(code, AppState.CallPhase.ANSWERING)
            IncomingCallOfferStore.clear(ctx, code)
            CallRequestDispatch.attempt(
                start = { start(ctx, ACTION_ANSWER_RELAY_CALL, arrayOf("code" to code)) },
                rejected = {
                    val message = "Android could not start call relay. Open NextNotif and check Phone and Microphone permissions."
                    if (AppState.callRelay.value.code == code &&
                        AppState.callRelay.value.phase == AppState.CallPhase.ANSWERING) {
                        AppState.finishCall(code, message)
                    }
                    AppState.setError(message)
                },
            )
        }

        fun endCall(ctx: Context, code: String) {
            start(ctx, ACTION_END_RELAY_CALL, arrayOf("code" to code))
        }

        fun beginOutgoingCall(ctx: Context, code: String, requestId: String, number: String) {
            start(ctx, ACTION_BEGIN_OUTGOING_CALL, arrayOf("code" to code, "request_id" to requestId, "number" to number))
        }

        fun placeOutgoingCall(ctx: Context, code: String, requestId: String, number: String, subscriptionId: Int?) {
            start(ctx, ACTION_PLACE_OUTGOING_CALL, arrayOf("code" to code, "request_id" to requestId,
                "number" to number, "subscription_id" to subscriptionId))
        }

        internal fun requiresPersistentService(ctx: Context): Boolean {
            val session = SessionStore.load(ctx)
            return session.relayEnabled && session.pairings.any {
                it.enabled && (it.role == Role.SENDER || !it.isFcmOnDemand)
            }
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
