package com.nextnotif.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.TimeUnit

/** The rooted sender pieces that the live cellular-call bridge actually needs. */
internal enum class GatewayCapability {
    CHECKING,
    AVAILABLE,
    ROOT_UNAVAILABLE,
    HELPER_UNAVAILABLE,
    PROBE_FAILED,
}

/** Small presentation bridge; capability checks remain outside global connection state. */
internal object GatewayCapabilityFeedback {
    private val _states = MutableStateFlow<Map<String, GatewayCapability>>(emptyMap())
    val states = _states.asStateFlow()

    @Synchronized
    fun set(code: String, capability: GatewayCapability) {
        _states.value = _states.value.toMutableMap().apply { set(code, capability) }
    }

    @Synchronized
    fun clear(code: String) {
        _states.value = _states.value.toMutableMap().apply { remove(code) }
    }
}

internal fun interface GatewayCapabilityProbe {
    fun probe(): GatewayCapability
}

/**
 * Root checks can wake Magisk and start a shell, so never perform one for each
 * pairing or answer retry. Results are deliberately time-limited: revoking
 * root or repairing the helper must eventually be observed without an app
 * restart.
 */
internal class CachedGatewayCapability(
    private val probe: GatewayCapabilityProbe,
    private val nowMs: () -> Long,
    private val successTtlMs: Long = 10 * 60_000L,
    private val failureTtlMs: Long = 60_000L,
) {
    private var cached: GatewayCapability? = null
    private var checkedAtMs: Long = Long.MIN_VALUE

    @Synchronized
    fun get(): GatewayCapability {
        val now = nowMs()
        val value = cached
        val ttl = if (value == GatewayCapability.AVAILABLE) successTtlMs else failureTtlMs
        if (value != null && now >= checkedAtMs && now - checkedAtMs < ttl) return value

        return probe.probe().also {
            cached = it
            checkedAtMs = now
        }
    }
}

internal class ProcessGatewayCapabilityProbe : GatewayCapabilityProbe {
    companion object {
        private const val ROOT_MISSING = 10
        private const val HELPER_MISSING = 11
        private const val SCRIPT =
            "[ \"\$(id -u)\" = 0 ] || exit $ROOT_MISSING; " +
                "[ -x /system/bin/nextnotif-tinymix ] || " +
                "[ -x /data/local/tmp/a520f-audio-tools/tinymix ] || exit $HELPER_MISSING"
    }

    override fun probe(): GatewayCapability = runCatching {
        val process = ProcessBuilder("su", "-c", SCRIPT)
            .redirectErrorStream(true)
            .start()
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroy()
            process.waitFor(250, TimeUnit.MILLISECONDS)
            if (process.isAlive) process.destroyForcibly()
            return@runCatching GatewayCapability.PROBE_FAILED
        }
        when (process.exitValue()) {
            0 -> GatewayCapability.AVAILABLE
            ROOT_MISSING -> GatewayCapability.ROOT_UNAVAILABLE
            HELPER_MISSING -> GatewayCapability.HELPER_UNAVAILABLE
            else -> GatewayCapability.PROBE_FAILED
        }
    }.getOrDefault(GatewayCapability.ROOT_UNAVAILABLE)
}

internal fun GatewayCapability.userMessage(): String = when (this) {
    GatewayCapability.CHECKING -> "NextNotif is checking live-call access on the sender phone"
    GatewayCapability.AVAILABLE -> "Live call relay is available"
    GatewayCapability.ROOT_UNAVAILABLE -> "Live call relay requires root access on the sender phone"
    GatewayCapability.HELPER_UNAVAILABLE -> "Live call relay requires the NextNotif gateway helper on the sender phone"
    GatewayCapability.PROBE_FAILED -> "NextNotif could not verify live-call access on the sender phone"
}
