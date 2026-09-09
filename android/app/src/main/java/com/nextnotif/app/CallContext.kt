package com.nextnotif.app

/**
 * Bridge between the [android.telecom.CallScreeningService] (which receives the
 * incoming number, even on API 31+ where the call-state listener does not) and the
 * relay service's call-state handling.
 */
object CallContext {
    private const val MAX_AGE_MS = 15_000L

    data class Screened(val number: String, val name: String?)

    @Volatile
    private var lastScreened: Pair<Screened, Long>? = null

    fun record(number: String, name: String? = null) {
        lastScreened = Screened(number, name) to System.currentTimeMillis()
    }

    fun takeScreened(): Screened? {
        val (screened, ts) = lastScreened ?: return null
        return if (System.currentTimeMillis() - ts > MAX_AGE_MS) null else screened
    }
}
