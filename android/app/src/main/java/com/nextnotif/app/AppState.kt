package com.nextnotif.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

object AppState {
    enum class ConnState { IDLE, CONNECTING, CONNECTED, LISTENING, DISCONNECTED }

    // The other end of a pairing: connection state + the device name it
    // advertised in its relay hello (e.g. "Xiaomi 23049PCD8G"), when known.
    data class PartnerState(
        val state: ConnState,
        val name: String? = null,
    )

    /** What happened, as data — the feed renders titles from these fields
     *  (localized, no log-string parsing) instead of pattern-matching text. */
    sealed interface EventKind {
        /** An SMS arrived from the partner phone. */
        data class SmsIn(val from: String, val name: String?, val body: String) : EventKind

        /** Incoming call state update from the partner phone. */
        data class CallIn(val number: String, val name: String?, val state: String) : EventKind

        /** This phone forwarded an SMS to a sender pairing. */
        data class SmsOut(val from: String, val name: String?) : EventKind

        /** This phone forwarded a call state to a sender pairing. */
        data class CallOut(val number: String, val name: String?, val state: String) : EventKind

        /** Own relay link connected. */
        data class Connected(val firebase: Boolean) : EventKind

        /** Own relay link closed (reason may be null). */
        data class Closed(val reason: String?) : EventKind

        /** Own relay link failed with an error. */
        data class Failed(val error: String) : EventKind

        /** Own relay link is retrying after a drop. */
        data class Reconnecting(val attempt: Int, val delayMs: Long) : EventKind

        /** Queued offline events were delivered after a reconnect. */
        data class Flushed(val count: Int) : EventKind

        /** An event was parked in the offline outbox (no route right now). */
        data class Queued(val type: String) : EventKind

        /** Retryable flush failure: some queued events still waiting. */
        data object StillWaiting : EventKind

        /** Anything else (generic relay lifecycle noise). */
        data class Info(val message: String) : EventKind
    }

    data class Entry(
        val ts: Long,
        val kind: EventKind,
        // The pairing this event belongs to, when it does. Global events
        // (no pairing context) carry null and show up in every view.
        val code: String? = null,
    )

    // Global (legacy-compat) — aggregated over all pairings so the Start/Stop
    // menu and anything reading it reflects the whole phone, not one pairing.
    private val _conn = MutableStateFlow(ConnState.IDLE)
    val conn: StateFlow<ConnState> = _conn.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _log = MutableStateFlow<List<Entry>>(emptyList())
    val log: StateFlow<List<Entry>> = _log.asStateFlow()

    private val _connSince = MutableStateFlow(0L)
    val connSince: StateFlow<Long> = _connSince.asStateFlow()

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

    fun push(kind: EventKind, code: String? = null) {
        val entry = Entry(System.currentTimeMillis(), kind, code)
        _log.value = (listOf(entry) + _log.value).take(50)
    }

    fun pushIncoming(type: String, data: JSONObject, code: String? = null) {
        val kind = when (type) {
            "sms" -> EventKind.SmsIn(
                from = data.optString("from"),
                name = data.optString("name").ifBlank { null },
                body = data.optString("body"),
            )
            "call" -> EventKind.CallIn(
                number = data.optString("number"),
                name = data.optString("name").ifBlank { null },
                state = data.optString("state"),
            )
            else -> EventKind.Info("$type $data")
        }
        push(kind, code)
    }

    fun pushOutgoing(type: String, data: JSONObject, code: String? = null) {
        val kind = when (type) {
            "sms" -> EventKind.SmsOut(
                from = data.optString("from"),
                name = data.optString("name").ifBlank { null },
            )
            "call" -> EventKind.CallOut(
                number = data.optString("number"),
                name = data.optString("name").ifBlank { null },
                state = data.optString("state"),
            )
            else -> EventKind.Info("$type $data")
        }
        push(kind, code)
    }
}

