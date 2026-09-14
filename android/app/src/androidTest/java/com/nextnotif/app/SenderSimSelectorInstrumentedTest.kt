package com.nextnotif.app

import android.content.res.Configuration
import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Synthetic SIM inventory; never requests a relay sync or sends a message. */
@RunWith(AndroidJUnit4::class)
class SenderSimSelectorInstrumentedTest {
    @Test fun choosesSecondSimAndReturnsToDefault() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val inventory = JSONObject("""{"state":"ready","default_id":7,"sims":[
            {"id":7,"slot":0,"name":"Personal","carrier":"Carrier A"},
            {"id":12,"slot":1,"name":"Work","carrier":"Carrier B"}]}""")
        var selected by mutableStateOf<Int?>(null)
        var dark by mutableStateOf(false)
        fun settle() { instrumentation.waitForIdleSync(); Thread.sleep(500) }
        fun click(text: String) {
            fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                if (node == null) return null
                if (node.text?.toString() == text || node.contentDescription?.toString() == text) return node
                for (index in 0 until node.childCount) find(node.getChild(index))?.let { return it }
                return null
            }
            var found: AccessibilityNodeInfo? = null
            for (attempt in 0 until 20) {
                found = find(instrumentation.uiAutomation.rootInActiveWindow)
                    ?: instrumentation.uiAutomation.windows.firstNotNullOfOrNull { find(it.root) }
                if (found != null) break
                Thread.sleep(250)
            }
            assertNotNull("Missing $text", found)
            var node = found!!
            while (!node.isClickable && node.parent != null) node = node.parent
            assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            settle()
        }
        fun capture(name: String) {
            val file = File(instrumentation.targetContext.getExternalFilesDir(null), name)
            file.outputStream().use { instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it) }
            assertTrue(file.length() > 1000)
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                val config = Configuration(LocalConfiguration.current).apply {
                    uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                        if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                }
                val density = LocalDensity.current
                CompositionLocalProvider(LocalConfiguration provides config,
                    LocalDensity provides Density(density.density, if (dark) 1.3f else 1f)) {
                    NextNotifTheme {
                        Surface(Modifier.fillMaxSize()) {
                            Column(Modifier.statusBarsPadding().navigationBarsPadding().padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("New message", style = MaterialTheme.typography.headlineMedium)
                                Text("Test sender · synthetic SIMs", style = MaterialTheme.typography.bodyMedium)
                                SenderSimSelector(inventory, selected, false, false, { selected = it }, {})
                                OutlinedTextField("Pick up the parcel at 4?", {}, Modifier.fillMaxWidth(), label = { Text("Message") })
                                Button({}, Modifier.fillMaxWidth(), enabled = false) { Text("Send SMS") }
                            }
                        }
                    }
                }
            } }
            settle()
            capture("sim-picker-initial.png")
            click("Use sender’s default")
            capture("sim-picker-menu.png")
            click("SIM 2 · Work · Carrier B")
            assertEquals(12, selected)
            capture("sim-picker-selected.png")
            instrumentation.runOnMainSync { dark = true }
            settle()
            capture("sim-picker-dark-large.png")
            click("SIM 2 · Work · Carrier B")
            click("Use sender’s default")
            assertNull(selected)
        }
    }
}
