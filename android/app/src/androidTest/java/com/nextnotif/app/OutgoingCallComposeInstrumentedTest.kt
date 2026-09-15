package com.nextnotif.app

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Render-only fixture. It never submits a call request or touches the modem. */
@RunWith(AndroidJUnit4::class)
class OutgoingCallComposeInstrumentedTest {
    @Test fun capturesOutgoingDialer() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val pairing = PairingInfo("000000", Role.RECEIVER, "https://example.invalid",
            transport = FcmOnDemand.TRANSPORT, label = "Samsung gateway", deviceToken = "fixture")
        val options = JSONObject("""{"state":"ready","default_id":7,"sims":[
            {"id":7,"slot":0,"name":"CARD 1","carrier":"IR-MCI"},
            {"id":12,"slot":1,"name":"CARD 2","carrier":"Irancell"}]}""")
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                NextNotifTheme { NewOutgoingCallScreen(listOf(pairing), {}, "+989121234567", options) }
            } }
            instrumentation.waitForIdleSync()
            Thread.sleep(500)
            val file = File(context.getExternalFilesDir(null), "outgoing-call.png")
            file.outputStream().use { instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it) }
            assertTrue(file.length() > 1000)
        }
    }
}
