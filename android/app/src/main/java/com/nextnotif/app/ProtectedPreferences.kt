package com.nextnotif.app

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** A single encrypted, atomically committed preference document per sensitive store. */
internal class ProtectedPreferences private constructor(
    private val source: SharedPreferences,
    private val codec: Codec,
) : SharedPreferences {
    internal interface Codec {
        fun seal(plain: String): String
        fun open(sealed: String): String
    }

    companion object {
        private const val DOCUMENT = "protected_document_v1"
        private val instances = mutableMapOf<String, ProtectedPreferences>()

        @Synchronized fun from(context: Context, name: String): SharedPreferences =
            instances.getOrPut(name) {
                ProtectedPreferences(
                    context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE),
                    KeystoreCodec("nextnotif.$name.v1"),
                ).also { it.migrate() }
            }

        fun migrateAll(context: Context) {
            listOf(
                "nextnotif_prefs", "nextnotif_message_history", "nextnotif_sms_send",
                "nextnotif_outbox", "nextnotif_incoming_call_offer",
            ).forEach { from(context, it) }
        }

        internal fun forTest(source: SharedPreferences, codec: Codec): SharedPreferences =
            ProtectedPreferences(source, codec).also { it.migrate() }
    }

    @Synchronized private fun migrate() {
        if (source.contains(DOCUMENT)) {
            // A previous interrupted migration can leave old keys; the encrypted
            // document is authoritative and those plaintext keys must go.
            val leftovers = source.all.keys - DOCUMENT
            if (leftovers.isNotEmpty()) {
                val editor = source.edit()
                leftovers.forEach(editor::remove)
                check(editor.commit()) { "Unable to remove legacy preferences" }
            }
            readDocument()
            return
        }
        val legacy = source.all.mapNotNull { (key, value) ->
            value?.takeIf { key != DOCUMENT }?.let { key to it }
        }.toMap()
        writeDocument(legacy)
    }

    private fun readDocument(): Map<String, Any> {
        val sealed = source.getString(DOCUMENT, null) ?: return emptyMap()
        val json = JSONObject(codec.open(sealed))
        return buildMap {
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val item = json.getJSONObject(key)
                val value: Any = when (item.getString("type")) {
                    "string" -> item.getString("value")
                    "boolean" -> item.getBoolean("value")
                    "int" -> item.getInt("value")
                    "long" -> item.getLong("value")
                    "float" -> item.getDouble("value").toFloat()
                    "set" -> item.getJSONArray("value").let { array ->
                        (0 until array.length()).map(array::getString).toSet()
                    }
                    else -> error("Unknown protected preference type")
                }
                put(key, value)
            }
        }
    }

    private fun writeDocument(values: Map<String, Any>) {
        val json = JSONObject()
        values.forEach { (key, value) ->
            val (type, encoded) = when (value) {
                is String -> "string" to value
                is Boolean -> "boolean" to value
                is Int -> "int" to value
                is Long -> "long" to value
                is Float -> "float" to value.toDouble()
                is Set<*> -> "set" to JSONArray(value.map { it as String })
                else -> error("Unsupported protected preference type")
            }
            json.put(key, JSONObject().put("type", type).put("value", encoded))
        }
        val editor = source.edit().putString(DOCUMENT, codec.seal(json.toString()))
        source.all.keys.filter { it != DOCUMENT }.forEach(editor::remove)
        check(editor.commit()) { "Unable to save protected preferences" }
    }

    override fun getAll(): Map<String, *> = synchronized(this) { readDocument() }
    override fun getString(key: String?, defValue: String?): String? = synchronized(this) {
        (readDocument()[key] as? String) ?: defValue
    }
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = synchronized(this) {
        @Suppress("UNCHECKED_CAST")
        (readDocument()[key] as? Set<String>)?.toMutableSet() ?: defValues
    }
    override fun getInt(key: String?, defValue: Int): Int = synchronized(this) { (readDocument()[key] as? Int) ?: defValue }
    override fun getLong(key: String?, defValue: Long): Long = synchronized(this) { (readDocument()[key] as? Long) ?: defValue }
    override fun getFloat(key: String?, defValue: Float): Float = synchronized(this) { (readDocument()[key] as? Float) ?: defValue }
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = synchronized(this) { (readDocument()[key] as? Boolean) ?: defValue }
    override fun contains(key: String?): Boolean = synchronized(this) { readDocument().containsKey(key) }
    override fun edit(): SharedPreferences.Editor = Editor()
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    private inner class Editor : SharedPreferences.Editor {
        private val changes = mutableMapOf<String, Any?>()
        private var clear = false
        override fun putString(key: String?, value: String?) = set(key, value)
        override fun putStringSet(key: String?, values: MutableSet<String>?) = set(key, values?.toSet())
        override fun putInt(key: String?, value: Int) = set(key, value)
        override fun putLong(key: String?, value: Long) = set(key, value)
        override fun putFloat(key: String?, value: Float) = set(key, value)
        override fun putBoolean(key: String?, value: Boolean) = set(key, value)
        override fun remove(key: String?) = set(key, null)
        override fun clear(): SharedPreferences.Editor { clear = true; return this }
        private fun set(key: String?, value: Any?): SharedPreferences.Editor {
            requireNotNull(key)
            changes[key] = value
            return this
        }
        override fun commit(): Boolean = synchronized(this@ProtectedPreferences) {
            val values = (if (clear) emptyMap() else readDocument()).toMutableMap()
            changes.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
            writeDocument(values)
            true
        }
        override fun apply() { commit() }
    }

    private class KeystoreCodec(private val alias: String) : Codec {
        private fun key(): SecretKey {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (store.getKey(alias, null) as? SecretKey)?.let { return it }
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true).build())
            }.generateKey()
        }
        override fun seal(plain: String): String {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
            val bytes = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            return Base64.encodeToString(bytes, Base64.NO_WRAP)
        }
        override fun open(sealed: String): String {
            val bytes = Base64.decode(sealed, Base64.NO_WRAP)
            require(bytes.size > 28) { "Invalid protected preferences" }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            }
            return String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
        }
    }
}
