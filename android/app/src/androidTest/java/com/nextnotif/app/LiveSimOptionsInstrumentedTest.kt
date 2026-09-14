package com.nextnotif.app

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/** Explicit device check: shares SIM metadata only. Never processes SMS commands or sends a text. */
@RunWith(AndroidJUnit4::class)
class LiveSimOptionsInstrumentedTest {
    @Test fun publishOrReadActualSenderSims() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveSimOptions") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val session = SessionStore.load(context)
        require(session.relayEnabled)
        val pairing = session.pairings.single { it.enabled && !it.isFirebase }
        val options: JSONObject
        if (pairing.role == Role.SENDER) {
            options = SmsSender.simOptions(context)
            assertEquals("Sender cannot read its SIMs", "ready", options.getString("state"))
            val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
            try {
                val base = pairing.server.trimEnd('/').replaceFirst("wss://", "https://").replaceFirst("ws://", "http://")
                val request = Request.Builder().url("$base/sms-fetch")
                    .header("X-NextNotif-Code", pairing.code)
                    .header("X-NextNotif-Token", requireNotNull(pairing.deviceToken))
                    .post(JSONObject().put("sim_options", options).toString().toRequestBody("application/json".toMediaType())).build()
                client.newCall(request).execute().use { response ->
                    assertEquals("SIM publication HTTP status", 200, response.code)
                    // Deliberately ignore commands in the response. No modem calls in this test.
                }
            } finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow() }
        } else {
            var result = SmsRelay.simOptions(context, pairing, true)
            for (attempt in 0 until 6) {
                if (result?.optString("state") == "ready") break
                delay(3000)
                result = SmsRelay.simOptions(context, pairing, false)
            }
            options = requireNotNull(result) { "Sender has not published SIM options" }
            assertEquals("Sender SIM list is unavailable", "ready", options.getString("state"))
        }
        val sims = options.getJSONArray("sims")
        assertTrue("No active sender SIM", sims.length() > 0)
        val labels = (0 until sims.length()).map { index ->
            val sim = sims.getJSONObject(index)
            "SIM ${sim.getInt("slot") + 1}: ${sim.optString("name")} (${sim.optString("carrier")})"
        }
        instrumentation.sendStatus(0, Bundle().apply {
            putString("stream", "\nrole=${pairing.role} sim_count=${sims.length()} options=$labels\n")
        })
        assertEquals(session.pairings.map { it.copy(deviceToken = null) },
            SessionStore.load(context).pairings.map { it.copy(deviceToken = null) })
    }
}
