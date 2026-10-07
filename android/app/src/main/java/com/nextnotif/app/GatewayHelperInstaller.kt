package com.nextnotif.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

internal enum class GatewayHelperInstallStatus {
    INSTALLED,
    ALREADY_INSTALLED,
    NO_PROFILE,
    ASSET_UNAVAILABLE,
    ROOT_UNAVAILABLE,
    INSTALL_FAILED,
    UNSUPPORTED_ABI,
}

internal data class GatewayHelperInstallResult(
    val status: GatewayHelperInstallStatus,
    val target: String? = null,
    val detail: String? = null,
)

/** Installs only a helper whose ABI and hardware profile were detected locally. */
internal object GatewayHelperInstaller {
    suspend fun install(context: Context, profile: GatewayHardwareProfile): GatewayHelperInstallResult =
        withContext(Dispatchers.IO) {
            if (profile.abi != GatewayCpuAbi.ARM64) {
                return@withContext GatewayHelperInstallResult(
                    GatewayHelperInstallStatus.UNSUPPORTED_ABI,
                    detail = "Only arm64-v8a helpers are enabled currently",
                )
            }
            val asset = profile.helperAsset
                ?: return@withContext GatewayHelperInstallResult(GatewayHelperInstallStatus.NO_PROFILE)
            val target = targetPath(profile)
            if (isExecutable(target)) {
                return@withContext GatewayHelperInstallResult(
                    GatewayHelperInstallStatus.ALREADY_INSTALLED,
                    target = target,
                )
            }

            val source = File.createTempFile("nextnotif-helper-", ".bin", context.cacheDir)
            try {
                runCatching {
                    context.assets.open(asset).use { input -> source.outputStream().use { input.copyTo(it) } }
                }.getOrElse {
                    return@withContext GatewayHelperInstallResult(
                        GatewayHelperInstallStatus.ASSET_UNAVAILABLE,
                        target = target,
                        detail = asset,
                    )
                }
                val command = "mkdir -p '${File(target).parent}' && cp '${source.absolutePath}' '$target' && chmod 0755 '$target'"
                val process = runCatching {
                    ProcessBuilder("/system/bin/su", "-c", command)
                        .redirectErrorStream(true)
                        .start()
                }.getOrElse {
                    return@withContext GatewayHelperInstallResult(
                        GatewayHelperInstallStatus.ROOT_UNAVAILABLE,
                        target = target,
                        detail = it.message,
                    )
                }
                val output = process.inputStream.bufferedReader().readText().trim()
                if (!CompatProcess.waitFor(process, 10_000)) {
                    CompatProcess.destroy(process)
                    return@withContext GatewayHelperInstallResult(
                        GatewayHelperInstallStatus.INSTALL_FAILED,
                        target = target,
                        detail = "root command timed out",
                    )
                }
                if (process.exitValue() == 0 && isExecutable(target)) {
                    GatewayHelperInstallResult(GatewayHelperInstallStatus.INSTALLED, target = target)
                } else {
                    GatewayHelperInstallResult(
                        GatewayHelperInstallStatus.INSTALL_FAILED,
                        target = target,
                        detail = output.ifBlank { "root command failed (${process.exitValue()})" },
                    )
                }
            } finally {
                source.delete()
            }
        }

    fun targetPath(profile: GatewayHardwareProfile): String =
        "/data/local/tmp/nextnotif-gateway/${profile.id.name.lowercase()}/${profile.abi.label}/gateway-helper"

    private fun isExecutable(path: String): Boolean {
        val file = File(path)
        return file.isFile && file.canExecute()
    }
}
