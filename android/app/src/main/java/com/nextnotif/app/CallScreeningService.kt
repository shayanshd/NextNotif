package com.nextnotif.app

import android.os.Build
import android.telecom.Call
import android.telecom.CallScreeningService
import android.util.Log

/**
 * Records the incoming call number (and the system-resolved contact name, when
 * available) for the relay. On API 31+ the normal call-state listener hides the
 * number, but a user-enabled call screening service still receives it. The user
 * must pick NextNotif as the call screening service in Settings for this to fire.
 */
class CallScreeningService : CallScreeningService() {
    override fun onScreenCall(callDetails: Call.Details) {
        val number = callDetails.handle?.let { uri ->
            when (uri.scheme) {
                "tel" -> uri.path ?: uri.toString().removePrefix("tel:")
                else -> uri.toString()
            }
        }
        // getContactDisplayName() is API 30+; callerDisplayName (API 23) covers the rest.
        val name = (if (Build.VERSION.SDK_INT >= 30) callDetails.contactDisplayName else null)
            ?: callDetails.callerDisplayName
            ?: null
        if (!number.isNullOrBlank()) {
            Log.i("CallScreening", "screened incoming call from $number ($name)")
            CallContext.record(number, name)
        }
    }
}
