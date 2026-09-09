package com.nextnotif.app

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

enum class Role { SENDER, RECEIVER }

data class SessionState(
    val role: Role? = null,
    val code: String? = null,
    val server: String = Config.DEFAULT_SERVER,
    val deviceToken: String? = null,
    val transport: String? = null,
    val fbConfig: String? = null,
    val secret: String? = null,
    val connected: Boolean = false,
    // Many-to-many source of truth: every pairing this phone participates in
    // (a phone may be sender in some and receiver in others). The
    // single-pairing fields above mirror the first entry for compatibility.
    val pairings: List<PairingInfo> = emptyList(),
)

object SessionStore {
    private const val P = "nextnotif_prefs"
    private const val K_CODE = "code"
    private const val K_ROLE = "role"
    private const val K_SERVER = "server"
    private const val K_DEVICE_TOKEN = "device_token"
    private const val K_TRANSPORT = "transport"
    private const val K_FB_CONFIG = "fb_config"
    private const val K_SECRET = "secret"
    private const val K_PAIRINGS = "pairings"

    internal fun saveTo(prefs: SharedPreferences, state: SessionState) {
        prefs.edit().apply {
            state.code?.let { putString(K_CODE, it) } ?: remove(K_CODE)
            state.role?.let { putString(K_ROLE, it.name) } ?: remove(K_ROLE)
            putString(K_SERVER, state.server)
            state.deviceToken?.let { putString(K_DEVICE_TOKEN, it) } ?: remove(K_DEVICE_TOKEN)
            state.transport?.let { putString(K_TRANSPORT, it) } ?: remove(K_TRANSPORT)
            state.fbConfig?.let { putString(K_FB_CONFIG, it) } ?: remove(K_FB_CONFIG)
            state.secret?.let { putString(K_SECRET, it) } ?: remove(K_SECRET)
            val list = toPairingsJson(state.pairings)
            if (list.length() > 0) putString(K_PAIRINGS, list.toString()) else remove(K_PAIRINGS)
            apply()
        }
    }

    internal fun loadFrom(prefs: SharedPreferences): SessionState {
        val role = prefs.getString(K_ROLE, null)?.let { runCatching { Role.valueOf(it) }.getOrNull() }
        val code = prefs.getString(K_CODE, null)
        val server = prefs.getString(K_SERVER, null) ?: Config.DEFAULT_SERVER
        val deviceToken = prefs.getString(K_DEVICE_TOKEN, null)
        val transport = prefs.getString(K_TRANSPORT, null)
        val fbConfig = prefs.getString(K_FB_CONFIG, null)
        val secret = prefs.getString(K_SECRET, null)
        // Many-to-many list first; pre-upgrade installs only have the legacy
        // single-pairing fields, which are migrated into a one-entry list so
        // existing phones keep their pairing across the upgrade. A corrupt
        // stored list also falls back to the migration.
        val storedPairings = if (prefs.contains(K_PAIRINGS)) {
            parsePairingsJson(prefs.getString(K_PAIRINGS, null))
        } else emptyList()
        val pairings: List<PairingInfo> = when {
            storedPairings.isNotEmpty() -> storedPairings
            code != null && role != null -> listOf(
                PairingInfo(
                    code = code,
                    role = role,
                    server = server,
                    transport = transport,
                    fbConfig = fbConfig,
                    secret = secret,
                    deviceToken = deviceToken,
                ),
            )
            else -> emptyList()
        }
        val connected = prefs.getBoolean("connected", false)
        return SessionState(
            role = role,
            code = code,
            server = server,
            deviceToken = deviceToken,
            transport = transport,
            fbConfig = fbConfig,
            secret = secret,
            connected = connected,
            pairings = pairings,
        )
    }

    internal fun toPairingsJson(list: List<PairingInfo>): JSONArray {
        val arr = JSONArray()
        for (p in list.distinctBy { it.code }) {
            arr.put(JSONObject().apply {
                put("code", p.code)
                put("role", p.role.name)
                put("server", p.server)
                p.transport?.let { put("transport", it) }
                p.fbConfig?.let { put("fbConfig", it) }
                p.secret?.let { put("secret", it) }
                p.deviceToken?.let { put("deviceToken", it) }
                p.label?.let { put("label", it) }
                put("enabled", p.enabled)
            })
        }
        return arr
    }

    internal fun parsePairingsJson(raw: String?): List<PairingInfo> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val code = o.optString("code").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val role = runCatching { Role.valueOf(o.optString("role")) }.getOrNull()
                    ?: return@mapNotNull null
                PairingInfo(
                    code = code,
                    role = role,
                    server = o.optString("server").ifBlank { Config.DEFAULT_SERVER },
                    transport = o.optString("transport").ifBlank { null },
                    fbConfig = o.optString("fbConfig").ifBlank { null },
                    secret = o.optString("secret").ifBlank { null },
                    deviceToken = o.optString("deviceToken").ifBlank { null },
                    label = o.optString("label").ifBlank { null },
                    enabled = o.optBoolean("enabled", true),
                )
            }.distinctBy { it.code }
        }.getOrNull() ?: emptyList()
    }

    internal fun clear(prefs: SharedPreferences) {
        prefs.edit().clear().apply()
    }

    fun save(ctx: Context, state: SessionState) {
        saveTo(ctx.getSharedPreferences(P, Context.MODE_PRIVATE), state)
    }

    fun load(ctx: Context): SessionState {
        return loadFrom(ctx.getSharedPreferences(P, Context.MODE_PRIVATE))
    }

    fun clear(ctx: Context) {
        clear(ctx.getSharedPreferences(P, Context.MODE_PRIVATE))
    }

    // The last address that completed a full relay handshake, per host. On
    // networks that lie about DNS, the honest answer is whatever actually
    // worked; it goes stale only when the provider rotates edge addresses,
    // which the probe detects (fast TCP refusal, not a stall).
    fun goodIp(ctx: Context, host: String): String? =
        ctx.getSharedPreferences(P, Context.MODE_PRIVATE).getString("good_ip_$host", null)

    fun rememberGoodIp(ctx: Context, host: String, ip: String) {
        ctx.getSharedPreferences(P, Context.MODE_PRIVATE).edit().putString("good_ip_$host", ip).apply()
    }
}
