package com.nextnotif.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OutgoingCallStatusTest {
    @Test fun oldCompletedCallCannotFinishCurrentCall() {
        val records = JSONArray()
            .put(JSONObject().put("id", "old").put("status", "ended"))
            .put(JSONObject().put("id", "current").put("status", "connected"))

        assertEquals("connected", currentOutgoingCallCommand(records, "current")?.getString("status"))
        assertNull(currentOutgoingCallCommand(records, "missing"))
    }
}
