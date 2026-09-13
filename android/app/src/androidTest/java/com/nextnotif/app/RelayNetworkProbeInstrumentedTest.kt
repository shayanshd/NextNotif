package com.nextnotif.app

import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.*
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Explicit transport probe only: no hello/auth, Answer, media, or permission grants. */
@RunWith(AndroidJUnit4::class)
class RelayNetworkProbeInstrumentedTest {
    @Test fun compareAppResolversWithoutAudio() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("networkProbe") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val before = SessionStore.load(context)
        val pairing = before.pairings.single { it.code == "454512" }
        require(pairing.role == Role.RECEIVER && pairing.server == "wss://relay.amberdogeorgia.com")
        for ((label, dns) in listOf("system" to Dns.SYSTEM, "custom" to DohFirstDns(context))) {
            val client = OkHttpClient.Builder().dns(dns)
                .connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).build()
            val result = AtomicReference("deadline")
            val done = CountDownLatch(1)
            val start = SystemClock.elapsedRealtime()
            val socket = client.newWebSocket(Request.Builder()
                .url("${pairing.server}/ws/receiver").header("X-NextNotif-Code", pairing.code).build(),
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        result.set("upgrade_${response.code}")
                        done.countDown()
                        webSocket.cancel() // Never send hello/auth or caller content.
                    }
                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        result.compareAndSet("deadline", "failure_${t.javaClass.simpleName}_http_${response?.code ?: 0}")
                        done.countDown()
                    }
                })
            try {
                done.await(15, TimeUnit.SECONDS)
                instrumentation.sendStatus(0, Bundle().apply {
                    putString("stream", "\nresolver=$label result=${result.get()} elapsed_ms=${SystemClock.elapsedRealtime() - start}\n")
                })
            } finally {
                socket.cancel()
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdownNow()
            }
        }
        assertTrue("Pairing settings changed during transport probe", before == SessionStore.load(context))
    }
}
