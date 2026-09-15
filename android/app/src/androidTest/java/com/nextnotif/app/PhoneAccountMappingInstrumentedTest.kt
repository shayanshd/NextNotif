package com.nextnotif.app

import android.content.Context
import android.telecom.TelecomManager
import android.telephony.SubscriptionManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Read-only OEM mapping probe. It never invokes TelecomManager.placeCall. */
@RunWith(AndroidJUnit4::class)
class PhoneAccountMappingInstrumentedTest {
    @Test fun reportsSafeMappingSignals() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val subscriptions = context.getSystemService(SubscriptionManager::class.java)
            .activeSubscriptionInfoList.orEmpty().sortedBy { it.simSlotIndex }
        val telecom = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        val accounts = telecom.callCapablePhoneAccounts
        println("MAPPING subscriptions=${subscriptions.size} accounts=${accounts.size}")
        subscriptions.forEach { sub ->
            assertEquals(1, accounts.count { phoneAccountMatchesSubscription(it.id, sub.subscriptionId, sub.iccId) })
            val matches = accounts.mapIndexed { index, handle ->
                val account = telecom.getPhoneAccount(handle)
                val id = handle.id
                val safeId = if (id.length <= 4) id else "len:${id.length},last:${id.takeLast(2)}"
                "account=$index,id=$safeId,sub=${id == sub.subscriptionId.toString()},icc=${id == sub.iccId}," +
                    "slot=${id == sub.simSlotIndex.toString()},startsIcc=${id.startsWith(sub.iccId)}," +
                    "endsIcc=${id.endsWith(sub.iccId)},digitsIcc=${id.filter(Char::isDigit) == sub.iccId}," +
                    "label=${account?.label}"
            }
            println("MAPPING sub=${sub.subscriptionId},slot=${sub.simSlotIndex}: ${matches.joinToString(" | ")}")
        }
    }
}
