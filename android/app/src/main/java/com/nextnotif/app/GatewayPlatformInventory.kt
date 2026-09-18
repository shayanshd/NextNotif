package com.nextnotif.app

import android.os.Build
import java.io.File

internal data class GatewayPlatformSnapshot(
    val manufacturer: String,
    val model: String,
    val device: String,
    val board: String,
    val release: String,
    val api: Int,
    val abis: List<String>,
    val kernel: String,
    val alsaCards: String,
    val alsaPcm: String,
    val policyFiles: List<String>,
)

internal object GatewayPlatformInventory {
    fun collect(): GatewayPlatformSnapshot {
        val policyCandidates = listOf(
            "/vendor/etc/audio_policy_configuration.xml",
            "/system/etc/audio_policy_configuration.xml",
            "/odm/etc/audio_policy_configuration.xml",
            "/vendor/etc/audio_policy.conf",
            "/system/etc/audio_policy.conf",
        )
        return GatewayPlatformSnapshot(
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            device = Build.DEVICE,
            board = Build.BOARD,
            release = Build.VERSION.RELEASE,
            api = Build.VERSION.SDK_INT,
            abis = Build.SUPPORTED_ABIS.toList(),
            kernel = read("/proc/version"),
            alsaCards = read("/proc/asound/cards"),
            alsaPcm = read("/proc/asound/pcm"),
            policyFiles = policyCandidates.filter { File(it).isFile },
        )
    }

    private fun read(path: String): String = runCatching {
        File(path).takeIf { it.isFile }?.readText()?.trim().orEmpty()
    }.getOrDefault("")
}
