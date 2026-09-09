package com.nextnotif.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ContactsLookupInstrumentedTest {

    private lateinit var ctx: Context
    // A number on this device whose normalized digits map to exactly one display name.
    private var savedNumber: String? = null
    private var savedName: String? = null

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        assumeTrue(
            "READ_CONTACTS not granted - run: adb shell pm grant com.nextnotif.app android.permission.READ_CONTACTS",
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) ==
                PackageManager.PERMISSION_GRANTED,
        )
        val namesByNorm = linkedMapOf<String, MutableSet<String>>()
        val cursor = ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ),
            null,
            null,
            null,
        )
        cursor?.use { c ->
            val nIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            val dIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            while (c.moveToNext()) {
                val num = c.getString(nIdx) ?: continue
                val name = c.getString(dIdx) ?: continue
                if (name.isBlank()) continue
                val digits = num.filter { it.isDigit() }.trimStart('0')
                if (digits.length < 7) continue
                namesByNorm.getOrPut(digits) { mutableSetOf() }.add(name)
            }
        }
        val pick = namesByNorm.entries.firstOrNull { it.value.size == 1 } ?: run {
            assumeTrue("no uniquely-named contact with a phone number on this device", false)
            error("unreachable")
        }
        val key = pick.key
        savedName = pick.value.single()
        // Recover the original raw number matching this normalized key.
        savedNumber = findRawNumber(key)
        assumeTrue("could not recover raw number", !savedNumber.isNullOrBlank())
    }

    private fun findRawNumber(normKey: String): String? {
        val cursor = ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            null,
            null,
            null,
        ) ?: return null
        cursor.use { c ->
            val nIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (c.moveToNext()) {
                val num = c.getString(nIdx) ?: continue
                if (num.filter { it.isDigit() }.trimStart('0') == normKey) return num
            }
        }
        return null
    }

    @Test
    fun exactNumberResolvesName() {
        val name = Contacts.lookupName(ctx, savedNumber!!)
        assertNotNull(name)
        assertEquals(savedName, name)
    }

    @Test
    fun reformattedNumberResolvesName() {
        val digits = savedNumber!!.filter { it.isDigit() }
        val name = Contacts.lookupName(ctx, digits)
        assertEquals(savedName, name)
    }

    @Test
    fun zeroPrefixedNumberResolvesName() {
        val digits = savedNumber!!.filter { it.isDigit() }
        val name = Contacts.lookupName(ctx, "0$digits")
        assertEquals(savedName, name)
    }

    @Test
    fun shortNumberReturnsNull() {
        assertNull(Contacts.lookupName(ctx, "12345"))
    }

    @Test
    fun unsavedNumberReturnsNull() {
        assertNull(Contacts.lookupName(ctx, "+1987654321098765"))
    }
}
