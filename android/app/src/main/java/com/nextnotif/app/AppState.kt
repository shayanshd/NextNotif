package com.nextnotif.app

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/** Preserve source time during delayed queue delivery; reject invalid/far-future values. */
internal fun communicationTimestamp(data: JSONObject, nowMs: Long): Long {
    val source = data.optLong("ts", 0L)
    return source.takeIf { it > 0L && it <= nowMs + 300_000L } ?: nowMs
}

object AppState {
    enum class ConnState { IDLE, CONNECTING, CONNECTED, LISTENING, ON_DEMAND, DISCONNECTED }

    enum class CallPhase { IDLE, RINGING, ANSWERING, CONNECTING, ACTIVE, RECONNECTING, ENDED, FAILED }

    enum class CommunicationKind { SMS, CALL }

    enum class CommunicationDirection { INCOMING, OUTGOING }

    data class CommunicationDetails(
        val kind: CommunicationKind,
        val direction: CommunicationDirection,
        val address: String? = null,
        val name: String? = null,
        val body: String? = null,
        val callState: String? = null,
        val smsStatus: String? = null,
        val smsDetail: String? = null,
    )

    data class CallRelayState(
        val code: String? = null,
        val number: String? = null,
        val name: String? = null,
        val phase: CallPhase = CallPhase.IDLE,
        val connectedAt: Long? = null,
        val detail: String? = null,
        val offeredAt: Long? = null,
    ) {
        val callerLabel: String
            get() = name?.takeIf { it.isNotBlank() }
                ?: number?.takeIf { it.isNotBlank() && it != "unknown" }
                ?: "Unknown caller"
    }

    // The other end of a pairing: connection state + the device name it
    // advertised in its relay hello (e.g. "Xiaomi 23049PCD8G"), when known.
    data class PartnerState(
        val state: ConnState,
        val name: String? = null,
    )

    data class Entry(
        val ts: Long,
        val tag: String,
        val message: String,
        // The pairing this event belongs to, when it does. Global events
        // (no pairing context) carry null and show up in every view.
        val code: String? = null,
        // Structured communication data keeps full message content and caller
        // identity available without reparsing a display sentence.
        val communication: CommunicationDetails? = null,
        val eventId: String? = null,
    )

    // Global (legacy-compat) — aggregated over all pairings so the Start/Stop
    // menu and anything reading it reflects the whole phone, not one pairing.
    private val _conn = MutableStateFlow(ConnState.IDLE)
    val conn: StateFlow<ConnState> = _conn.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _log = MutableStateFlow<List<Entry>>(emptyList())
    val log: StateFlow<List<Entry>> = _log.asStateFlow()

    private val _messages = MutableStateFlow<List<Entry>>(emptyList())
    val messages: StateFlow<List<Entry>> = _messages.asStateFlow()

    @Volatile private var messageStore: MessageStore? = null

    private val _connSince = MutableStateFlow(0L)
    val connSince: StateFlow<Long> = _connSince.asStateFlow()

    private val _callRelay = MutableStateFlow(CallRelayState())
    val callRelay: StateFlow<CallRelayState> = _callRelay.asStateFlow()

    // This phone's own relay connection, per pairing code.
    private val _connStates = MutableStateFlow<Map<String, ConnState>>(emptyMap())
    val connStates: StateFlow<Map<String, ConnState>> = _connStates.asStateFlow()

    // The other end of each pairing (partner connection state + device name),
    // keyed by code. Updated from the server /pair/{code}/status endpoint.
    private val _partnerStates = MutableStateFlow<Map<String, PartnerState>>(emptyMap())
    val partnerStates: StateFlow<Map<String, PartnerState>> = _partnerStates.asStateFlow()

    // Last connection error, per pairing code.
    private val _pairingErrors = MutableStateFlow<Map<String, String?>>(emptyMap())
    val pairingErrors: StateFlow<Map<String, String?>> = _pairingErrors.asStateFlow()

    fun setConn(state: ConnState) {
        _conn.value = state
        if (state == ConnState.CONNECTED) {
            if (_connSince.value == 0L) _connSince.value = System.currentTimeMillis()
        } else {
            _connSince.value = 0L
        }
    }

    /** This phone's own relay state for one pairing; refreshes the aggregate. */
    fun setConnState(code: String, state: ConnState) {
        _connStates.value = _connStates.value.toMutableMap().apply { set(code, state) }
        refreshAggregateConn()
    }

    /** Set the partner (other end of the pairing) connection state for this code. */
    fun setPartnerState(code: String, state: ConnState, name: String? = null) {
        val map = _partnerStates.value.toMutableMap()
        map[code] = PartnerState(state, name)
        _partnerStates.value = map
    }

    fun setPairingError(code: String, message: String?) {
        _pairingErrors.value = _pairingErrors.value.toMutableMap().apply { set(code, message) }
    }

    /** Connected wins, then connecting, then any pairing with a state, else idle. */
    fun refreshAggregateConn() {
        val states = _connStates.value.values
        val next = when {
            states.isEmpty() -> ConnState.IDLE
            ConnState.CONNECTED in states -> ConnState.CONNECTED
            ConnState.ON_DEMAND in states -> ConnState.ON_DEMAND
            ConnState.CONNECTING in states -> ConnState.CONNECTING
            else -> ConnState.DISCONNECTED
        }
        setConn(next)
    }

    /** Wipe all per-pairing + aggregate state (service stopped). */
    fun clearStates() {
        _connStates.value = emptyMap()
        _partnerStates.value = emptyMap()
        _pairingErrors.value = emptyMap()
        setConn(ConnState.IDLE)
    }

    /** Remove one pairing's per-pairing state and refresh the aggregate. */
    fun clearPairingState(code: String) {
        _connStates.value = _connStates.value.toMutableMap().apply { remove(code) }
        _partnerStates.value = _partnerStates.value.toMutableMap().apply { remove(code) }
        _pairingErrors.value = _pairingErrors.value.toMutableMap().apply { remove(code) }
        refreshAggregateConn()
    }

    fun setError(message: String?) {
        _lastError.value = message
    }

    /** Safe to call from activities, services, and push handlers. */
    @Synchronized
    fun initializeMessages(context: Context) {
        if (messageStore != null) return
        val store = MessageStore.from(context)
        messageStore = store
        _messages.value = store.load()
    }

    @Synchronized
    fun clearMessages(context: Context) {
        val store = messageStore ?: MessageStore.from(context).also { messageStore = it }
        store.clear()
        _messages.value = emptyList()
    }

    @Synchronized
    fun setIncomingCall(code: String, number: String?, name: String?, offeredAt: Long? = null) {
        if (_callRelay.value.phase in setOf(CallPhase.ANSWERING, CallPhase.CONNECTING,
                CallPhase.ACTIVE, CallPhase.RECONNECTING)) return
        _callRelay.value = CallRelayState(
            code = code,
            number = number,
            name = name,
            phase = CallPhase.RINGING,
            offeredAt = offeredAt,
        )
    }

    @Synchronized
    fun observeIncomingCall(code: String, state: String, liveAvailable: Boolean,
                            number: String?, name: String?, offeredAt: Long? = null) {
        if (state == "RINGING" && liveAvailable) setIncomingCall(code, number, name, offeredAt)
        // A passive alert may dismiss an unanswered call, but cannot own teardown
        // of active media. The service handles active-session end controls.
        if (state == "IDLE" && _callRelay.value.phase == CallPhase.RINGING) finishCall(code)
    }

    @Synchronized
    fun updateCall(
        code: String,
        phase: CallPhase,
        detail: String? = null,
        number: String? = null,
        name: String? = null,
    ) {
        val current = _callRelay.value
        val sameCall = current.code == code
        _callRelay.value = current.copy(
            code = code,
            number = number ?: current.number,
            name = name ?: current.name,
            phase = phase,
            connectedAt = when {
                phase == CallPhase.ACTIVE && current.connectedAt == null -> System.currentTimeMillis()
                phase == CallPhase.ACTIVE || phase == CallPhase.RECONNECTING -> current.connectedAt
                else -> null
            },
            detail = detail,
        ).let { if (sameCall) it else it.copy(connectedAt = if (phase == CallPhase.ACTIVE) System.currentTimeMillis() else null) }
    }

    @Synchronized
    fun finishCall(code: String, failed: String? = null) {
        val current = _callRelay.value
        if (current.code != code) return
        _callRelay.value = current.copy(
            phase = if (failed == null) CallPhase.ENDED else CallPhase.FAILED,
            connectedAt = null,
            detail = failed,
        )
    }

    fun push(tag: String, message: String, code: String? = null, eventId: String? = null) {
        pushEntry(Entry(System.currentTimeMillis(), tag, message, code, eventId = eventId))
    }

    fun hasStoredEvent(code: String?, eventId: String?): Boolean =
        eventId != null && messageStore?.hasEvent(code, eventId) == true

    private fun pushEntry(entry: Entry) {
        if (MessageStore.isHumanCommunication(entry)) {
            recordMessage(entry)
        }
        _log.value = (listOf(entry) + _log.value).take(50)
    }

    @Synchronized
    private fun recordMessage(entry: Entry) {
        messageStore?.let { _messages.value = it.append(entry) }
    }

    fun pushIncoming(type: String, data: JSONObject, code: String? = null, eventId: String? = null) {
        when (type) {
            "sms" -> {
                val from = data.optString("from")
                val name = data.optString("name").ifBlank { null }
                val label = if (name != null) "$name ($from)" else from
                val body = data.optString("body")
                pushEntry(
                    Entry(
                        ts = communicationTimestamp(data, System.currentTimeMillis()),
                        tag = "IN",
                        message = "SMS from $label: $body",
                        code = code,
                        eventId = eventId,
                        communication = CommunicationDetails(
                            kind = CommunicationKind.SMS,
                            direction = CommunicationDirection.INCOMING,
                            address = from,
                            name = name,
                            body = body,
                        ),
                    ),
                )
            }
            "call" -> {
                val number = data.optString("number")
                val name = data.optString("name").ifBlank { null }
                val label = when {
                    name != null && number != "unknown" -> "$name ($number)"
                    name != null -> name
                    else -> number
                }
                val state = data.optString("state")
                pushEntry(
                    Entry(
                        ts = communicationTimestamp(data, System.currentTimeMillis()),
                        tag = "IN",
                        message = "Call $state from $label",
                        code = code,
                        eventId = eventId,
                        communication = CommunicationDetails(
                            kind = CommunicationKind.CALL,
                            direction = CommunicationDirection.INCOMING,
                            address = number,
                            name = name,
                            callState = state,
                        ),
                    ),
                )
            }
            else -> push("IN", "$type $data", code)
        }
    }

    fun pushOutgoing(type: String, data: JSONObject, code: String? = null, eventId: String? = null) {
        when (type) {
            "sms" -> {
                val from = data.optString("from")
                val name = data.optString("name").ifBlank { null }
                val label = if (name != null) "$name ($from)" else from
                val body = data.optString("body")
                pushEntry(
                    Entry(
                        ts = communicationTimestamp(data, System.currentTimeMillis()),
                        tag = "OUT",
                        message = "SMS → $label: $body",
                        code = code,
                        communication = CommunicationDetails(
                            kind = CommunicationKind.SMS,
                            direction = CommunicationDirection.OUTGOING,
                            address = from,
                            name = name,
                            body = body,
                        ),
                    ),
                )
            }
            "call" -> {
                val number = data.optString("number")
                val name = data.optString("name").ifBlank { null }
                val label = when {
                    name != null && number != "unknown" -> "$name ($number)"
                    name != null -> name
                    else -> number
                }
                val state = data.optString("state")
                pushEntry(
                    Entry(
                        ts = communicationTimestamp(data, System.currentTimeMillis()),
                        tag = "OUT",
                        message = "Call $state → $label",
                        code = code,
                        eventId = eventId,
                        communication = CommunicationDetails(
                            kind = CommunicationKind.CALL,
                            direction = CommunicationDirection.OUTGOING,
                            address = number,
                            name = name,
                            callState = state,
                        ),
                    ),
                )
            }
            else -> push("OUT", "$type $data", code)
        }
    }
}
