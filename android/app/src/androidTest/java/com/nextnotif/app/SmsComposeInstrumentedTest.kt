package com.nextnotif.app

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Render-only fixture. Does not enqueue SMS, modify pairings, or enable sending. */
@RunWith(AndroidJUnit4::class)
class SmsComposeInstrumentedTest {
    @Test fun capturesComposerAndConversationWithoutSending() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val pairing = PairingInfo("000000", Role.RECEIVER, "https://example.invalid", transport = FcmOnDemand.TRANSPORT, label = "Test sender")
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                NextNotifTheme { NewSmsScreen(listOf(pairing), {}, {}, initialNumber = "+15551234567") }
            } }
            instrumentation.waitForIdleSync()
            Thread.sleep(600)
            fun capture(name: String) {
                val bitmap = instrumentation.uiAutomation.takeScreenshot()
                val file = File(context.getExternalFilesDir(null), name)
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                assertTrue(file.length() > 1000)
            }
            capture("sms-new-message.png")
            scenario.onActivity { activity -> activity.setContent {
                NextNotifTheme {
                    Surface { Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                        Text("Test conversation", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp))
                        Column(Modifier.weight(1f).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            ConversationBubble(AppState.Entry(System.currentTimeMillis(), "IN", "SMS from +15551234567: Hello",
                                communication = AppState.CommunicationDetails(AppState.CommunicationKind.SMS,
                                    AppState.CommunicationDirection.INCOMING, address = "+15551234567", body = "Can you pick up the parcel today?")))
                            ConversationBubble(AppState.Entry(System.currentTimeMillis(), "OUT", "SMS → +15551234567: Yes",
                                communication = AppState.CommunicationDetails(AppState.CommunicationKind.SMS,
                                    AppState.CommunicationDirection.OUTGOING, address = "+15551234567", body = "Yes, I will be there at 4.", smsStatus = "queued")))
                        }
                        SmsComposer(pairing, "+15551234567", "fixture")
                    } }
                }
            } }
            instrumentation.waitForIdleSync()
            Thread.sleep(600)
            capture("sms-conversation.png")
        }
    }
}
