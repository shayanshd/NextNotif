package com.nextnotif.app

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

/** A non-recording inventory plus source probe used by Gateway Diagnostics. */
internal data class HardwareCompatibilityReport(
    val profile: GatewayHardwareProfile,
    val platform: GatewayPlatformSnapshot,
    val helperInstallation: GatewayHelperInstallResult,
    val capability: GatewayCapability,
    val audioDevices: List<AudioDeviceSnapshot>,
    val sources: List<AudioProbeResult>,
    val liveCallVerdict: LiveCallVerdict,
)

internal data class AudioDeviceSnapshot(
    val name: String,
    val direction: String,
    val type: Int,
    val sampleRates: List<Int>,
)

internal enum class LiveCallVerdict {
    READY_TO_VALIDATE,
    NEEDS_ROOT_OR_HELPER,
    NEEDS_CALL_TEST,
    NO_ROUTE_DETECTED,
}

internal object HardwareCompatibilityScanner {
    suspend fun scan(
        context: Context,
        onProgress: (String) -> Unit,
    ): HardwareCompatibilityReport {
        onProgress("Checking root and gateway helper")
        val profile = GatewayHardwareProfiles.detect()
        val platform = GatewayPlatformInventory.collect()
        val helperInstallation = GatewayHelperInstaller.install(context, profile)
        val capability = ProcessGatewayCapabilityProbe().probe()
        onProgress("Inventorying audio devices")
        val devices = audioDevices(context)
        onProgress("Testing call audio sources")
        val sources = GatewayAudioProbe.run(onProgress)
        val downlink = sources.firstOrNull { it.source == "VOICE_DOWNLINK" }
        val uplink = sources.firstOrNull { it.source == "VOICE_UPLINK" }
        val verdict = when {
            capability != GatewayCapability.AVAILABLE -> LiveCallVerdict.NEEDS_ROOT_OR_HELPER
            downlink?.status == ProbeStatus.SIGNAL && uplink?.status == ProbeStatus.SIGNAL ->
                LiveCallVerdict.READY_TO_VALIDATE
            downlink?.status == ProbeStatus.SILENT && uplink?.status == ProbeStatus.SILENT ->
                LiveCallVerdict.NEEDS_CALL_TEST
            downlink?.status == ProbeStatus.BLOCKED || uplink?.status == ProbeStatus.BLOCKED ->
                LiveCallVerdict.NO_ROUTE_DETECTED
            else -> LiveCallVerdict.NEEDS_CALL_TEST
        }
        return HardwareCompatibilityReport(profile, platform, helperInstallation, capability, devices, sources, verdict)
    }

    private fun audioDevices(context: Context): List<AudioDeviceSnapshot> {
        val manager = context.getSystemService(AudioManager::class.java) ?: return emptyList()
        return manager.getDevices(AudioManager.GET_DEVICES_ALL).map { device ->
            AudioDeviceSnapshot(
                name = device.productName?.toString()?.takeIf { it.isNotBlank() }
                    ?: "Audio device ${device.id}",
                direction = when {
                    device.isSource && device.isSink -> "input/output"
                    device.isSource -> "input"
                    else -> "output"
                },
                type = device.type,
                sampleRates = device.sampleRates.toList(),
            )
        }
    }
}

internal fun LiveCallVerdict.userMessage(): String = when (this) {
    LiveCallVerdict.READY_TO_VALIDATE -> "Both call paths returned signal. Run a real call test before enabling live calls."
    LiveCallVerdict.NEEDS_ROOT_OR_HELPER -> "Root access and the matching gateway helper are required."
    LiveCallVerdict.NEEDS_CALL_TEST -> "The phone is idle, so call paths need validation during an active call."
    LiveCallVerdict.NO_ROUTE_DETECTED -> "Android or this firmware blocked one or more cellular audio paths."
}

internal fun AudioDeviceSnapshot.summary(): String {
    val rates = sampleRates.takeIf { it.isNotEmpty() }?.joinToString("/") { "${it / 1000}k" }
    return buildString {
        append(direction)
        append(" · type ")
        append(type)
        if (rates != null) append(" · ").append(rates)
    }
}
