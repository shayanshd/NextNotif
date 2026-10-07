package com.nextnotif.app

import android.annotation.SuppressLint
import android.os.Build
import java.util.concurrent.TimeUnit

/** Process timeout APIs arrived in API 26; older test devices poll exitValue. */
internal object CompatProcess {
    // sdkInt is injectable for host tests; production always uses Build.VERSION.SDK_INT.
    @SuppressLint("NewApi")
    fun waitFor(process: Process, timeoutMs: Long, sdkInt: Int = Build.VERSION.SDK_INT): Boolean {
        require(timeoutMs > 0)
        if (sdkInt >= 26) return process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (true) {
            try {
                process.exitValue()
                return true
            } catch (_: IllegalThreadStateException) {
                // Still running.
            }
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) return false
            Thread.sleep(TimeUnit.NANOSECONDS.toMillis(remaining).coerceIn(1, 25))
        }
    }

    @SuppressLint("NewApi")
    fun destroy(process: Process, sdkInt: Int = Build.VERSION.SDK_INT) {
        if (sdkInt >= 26) process.destroyForcibly() else process.destroy()
    }
}

/** Fixed device commands must not hold the call-control queue indefinitely. */
internal object BoundedCommand {
    fun successful(process: Process, timeoutMs: Long, sdkInt: Int = Build.VERSION.SDK_INT): Boolean {
        require(timeoutMs > 0)
        return try {
            if (!CompatProcess.waitFor(process, timeoutMs, sdkInt)) {
                CompatProcess.destroy(process, sdkInt)
                false
            } else process.exitValue() == 0
        } catch (_: InterruptedException) {
            CompatProcess.destroy(process, sdkInt)
            Thread.currentThread().interrupt()
            false
        } finally {
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
            runCatching { process.outputStream.close() }
        }
    }
}
