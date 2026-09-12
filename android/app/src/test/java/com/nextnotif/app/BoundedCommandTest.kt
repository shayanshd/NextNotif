package com.nextnotif.app

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

class BoundedCommandTest {
    private class FakeProcess(val finished: Boolean, val result: Int) : Process() {
        var destroyed = false
        var timeout = 0L
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun getInputStream() = ByteArrayInputStream(byteArrayOf())
        override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
        override fun waitFor() = error("Unbounded wait must never be used")
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            this.timeout = unit.toMillis(timeout); return finished
        }
        override fun exitValue() = result
        override fun destroy() { destroyed = true }
        override fun destroyForcibly(): Process { destroyed = true; return this }
    }
    @Test fun acceptsOnlySuccessfulExit() {
        assertTrue(BoundedCommand.successful(FakeProcess(true, 0), 5000))
        assertFalse(BoundedCommand.successful(FakeProcess(true, 1), 5000))
    }
    @Test fun timeoutKillsProcessAndNeverUsesUnboundedWait() {
        val process = FakeProcess(false, 0)
        assertFalse(BoundedCommand.successful(process, 5000))
        assertTrue(process.destroyed)
        assertEquals(5000L, process.timeout)
    }
}
