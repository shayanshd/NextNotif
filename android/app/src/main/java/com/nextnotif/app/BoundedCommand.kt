package com.nextnotif.app

import java.util.concurrent.TimeUnit

/** Fixed device commands must not hold the call-control queue indefinitely. */
internal object BoundedCommand {
    fun successful(process: Process, timeoutMs: Long): Boolean {
        require(timeoutMs > 0)
        return try {
            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                false
            } else process.exitValue() == 0
        } catch (_: InterruptedException) {
            process.destroyForcibly()
            Thread.currentThread().interrupt()
            false
        } finally {
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
            runCatching { process.outputStream.close() }
        }
    }
}
