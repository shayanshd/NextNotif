package com.nextnotif.app

import android.os.Build

internal enum class GatewayCpuAbi(val label: String) {
    ARM32("armeabi-v7a"),
    ARM64("arm64-v8a"),
    UNKNOWN("unknown"),
}

internal enum class GatewayProfileId {
    SAMSUNG_A520F,
    QUALCOMM_MSM8974_VOICE,
    GENERIC_ARM64,
    UNKNOWN,
}

internal data class GatewayHardwareProfile(
    val id: GatewayProfileId,
    val displayName: String,
    val abi: GatewayCpuAbi,
    val helperAsset: String?,
    val expectedControls: List<String>,
    val capturePcm: Int?,
    val playbackPcm: Int?,
)

internal object GatewayHardwareProfiles {
    fun detect(): GatewayHardwareProfile {
        val abi = when {
            Build.SUPPORTED_ABIS.any { it == "armeabi-v7a" || it == "armeabi" } -> GatewayCpuAbi.ARM32
            Build.SUPPORTED_ABIS.any { it == "arm64-v8a" } -> GatewayCpuAbi.ARM64
            else -> GatewayCpuAbi.UNKNOWN
        }
        val board = Build.BOARD.lowercase()
        val device = Build.DEVICE.lowercase()
        val model = Build.MODEL.lowercase()
        return when {
            device == "a5y17lte" || model.contains("sm-a520f") -> GatewayHardwareProfile(
                id = GatewayProfileId.SAMSUNG_A520F,
                displayName = "Samsung A520F voice gateway",
                abi = abi,
                helperAsset = "gateway/samsung-a520f/${abi.label}/gateway-helper",
                expectedControls = listOf("AudioMixer CH2 DOUT Select"),
                capturePcm = null,
                playbackPcm = null,
            )
            board.contains("msm8974") || board.contains("taiko") || device == "htc_m8" -> GatewayHardwareProfile(
                id = GatewayProfileId.QUALCOMM_MSM8974_VOICE,
                displayName = "Qualcomm MSM8974 voice gateway",
                abi = abi,
                helperAsset = "gateway/msm8974/${abi.label}/voice-bridge",
                expectedControls = listOf(
                    "SLIM_0_RX_Voice Mixer CSVoice",
                    "Voice_Tx Mixer SLIM_0_TX_Voice",
                ),
                capturePcm = 55,
                playbackPcm = 57,
            )
            else -> GatewayHardwareProfile(
                id = if (abi == GatewayCpuAbi.ARM64) GatewayProfileId.GENERIC_ARM64 else GatewayProfileId.UNKNOWN,
                displayName = if (abi == GatewayCpuAbi.ARM64) {
                    "Generic ARM64 audio gateway"
                } else {
                    "Unsupported audio gateway"
                },
                abi = abi,
                helperAsset = null,
                expectedControls = emptyList(),
                capturePcm = null,
                playbackPcm = null,
            )
        }
    }
}
