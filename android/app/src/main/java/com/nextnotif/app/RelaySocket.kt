package com.nextnotif.app

import android.os.Build
import android.util.Log
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.net.URL
import java.net.URLEncoder
import java.security.KeyStore
import java.security.cert.Certificate
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.PKIXParameters
import java.security.cert.TrustAnchor
import java.security.cert.X509Certificate
import java.security.cert.CertPathValidator
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject

internal fun receiverDeliveryMode(role: Role, fcmOnDemand: Boolean): String? =
    if (role == Role.RECEIVER && fcmOnDemand) "fcm" else null

class RelaySocket(
    private val context: android.content.Context,
    private val server: String,
    private val role: Role,
    private val code: String,
    private val deviceToken: String? = null,
    private var fcmToken: String? = null,
    private val fcmOnDemand: Boolean = false,
    private val onEvent: (Event) -> Unit,
) {
    companion object {
        private const val TAG = "RelaySocket"
        // At G.711's 8 KB/s this is about 500 ms. Once the TCP writer reaches
        // this limit, dropping fresh media is preferable to growing call delay
        // and starving WebSocket control frames/pings behind old audio.
        private const val MAX_MEDIA_WRITE_QUEUE_BYTES = 4_096L
    }

    sealed class Event {
        data object Open : Event()
        data class Closed(val reason: String) : Event()
        data class Failure(val error: String) : Event()
        data class Incoming(val type: String, val data: JSONObject, val eventId: String? = null) : Event()
        data class Binary(val bytes: ByteString) : Event()
        /** Aggregate instrumentation signal only; no media bytes are retained. */
        data object MediaDropped : Event()
        data class AuthOk(val deviceToken: String) : Event()
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        // The receiver is the only role that holds a socket. Thirty seconds is
        // frequent enough to survive common mobile NAT/proxy idle windows while
        // cutting heartbeat radio wakeups by 6x versus the old 5-second value.
        .pingInterval(30, TimeUnit.SECONDS)
        // A sinkhole stalls TLS; a real edge answers in well under 5 s, so a
        // short connect budget makes bad addresses fail over fast.
        .connectTimeout(5, TimeUnit.SECONDS)
        .dns(DohFirstDns(context.applicationContext))
        .build()

    private var ws: WebSocket? = null
    @Volatile private var lastMediaDropLogAt = 0L
    @Volatile var isOpen: Boolean = false
        private set
    @Volatile var isAuthenticated: Boolean = false
        private set

    fun connect() {
        val url = "$server/ws/${role.name.lowercase()}"
        val req = Request.Builder()
            .url(url)
            .header("X-NextNotif-Code", code)
            .build()
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                isOpen = true
                // Open the handshake; advertise this device's name so the
                // partner's UI can label the peer ("Xiaomi 23049PCD8G").
                webSocket.send(
                    JSONObject().apply {
                        put("type", "hello")
                        put("device_name", deviceName())
                        fcmToken?.let { put("fcm_token", it) }
                    }.toString(),
                )
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching {
                    val obj = JSONObject(text)
                    val type = obj.optString("type", "unknown")
                    if (type == "handshake") {
                        val token = obj.optString("token", "")
                        val auth = JSONObject().apply {
                            put("type", "auth")
                            put("token", token)
                            put("code", code)
                            receiverDeliveryMode(role, fcmOnDemand)?.let { put("delivery_mode", it) }
                            deviceToken?.let { put("device_token", it) }
                            fcmToken?.let { put("fcm_token", it) }
                        }
                        // Only report Open once we can actually send; the service
                        // flushes its outbox on Open and send() is gated on auth.
                        if (webSocket.send(auth.toString())) {
                            isAuthenticated = true
                            onEvent(Event.Open)
                        }
                        return@runCatching
                    }
                    if (type == "auth") return@runCatching
                    if (type == "auth_ok") {
                        val dt = obj.optString("device_token", "")
                        if (dt.isNotEmpty()) onEvent(Event.AuthOk(dt))
                        return@runCatching
                    }
                    val data = obj.optJSONObject("data") ?: JSONObject()
                    onEvent(Event.Incoming(type, data, obj.optString("event_id").ifBlank { null }))
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (isAuthenticated) onEvent(Event.Binary(bytes))
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                isOpen = false
                isAuthenticated = false
                onEvent(Event.Closed(reason))
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                isOpen = false
                isAuthenticated = false
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                isOpen = false
                isAuthenticated = false
                onEvent(Event.Failure(t.message ?: "unknown"))
            }
        })
    }

    fun send(type: String, data: JSONObject): Boolean {
        if (!isAuthenticated) return false
        val payload = JSONObject().apply {
            put("type", type)
            put("data", data)
        }
        return try {
            ws?.send(payload.toString()) ?: false
        } catch (t: Throwable) {
            false
        }
    }

    /** Send an ephemeral media frame. The server never persists binary data. */
    fun sendBinary(bytes: ByteArray): Boolean {
        if (!isAuthenticated) return false
        val socket = ws ?: return false
        if (socket.queueSize() >= MAX_MEDIA_WRITE_QUEUE_BYTES) {
            val now = System.currentTimeMillis()
            if (now - lastMediaDropLogAt >= 1_000L) {
                lastMediaDropLogAt = now
                Log.w(TAG, "dropping live audio before TCP queue grows (${socket.queueSize()} bytes queued)")
            }
            onEvent(Event.MediaDropped)
            return true
        }
        return try {
            socket.send(ByteString.of(*bytes))
        } catch (_: Throwable) {
            false
        }
    }

    /** Register a token obtained after the socket handshake completed. */
    fun updateFcmToken(token: String) {
        fcmToken = token
        if (!isAuthenticated) return
        ws?.send(
            JSONObject().apply {
                put("type", "fcm_token")
                put("data", JSONObject().put("token", token))
            }.toString(),
        )
    }

    fun close() {
        ws?.close(1000, "bye")
        ws = null
        isOpen = false
        isAuthenticated = false
    }
}

/**
 * Resolves the relay host against whatever source the current network tells
 * the truth on, in priority order:
 *
 * 1. The last address that completed a full relay handshake (persisted via
 *    [SessionStore.rememberGoodIp]) — on a lying network the honest answer is
 *    whatever actually worked.
 * 2. DNS-over-HTTPS — IP-literal endpoints (the lookup cannot depend on the
 *    poisoned resolver) and domain endpoints (for networks that block direct
 *    egress to 8.8.8.8/1.1.1.1 but allow the Google/Cloudflare domains).
 * 3. The system resolver.
 *
 * The home ISP (observed) answers the relay name with *real* Cloudflare
 * anycast IPs or with a domestic sinkhole pair — and flips between them from
 * one query to the next, which is why the relay "sometimes connects and
 * sometimes doesn't". Both answers sit inside published Cloudflare address
 * space, so range checks cannot tell them apart; only a timed TLS handshake
 * can (the sinkhole stalls, a real edge answers in hundreds of ms).
 * Candidates are verified with a 3 s TLS probe, stopping at the first good
 * one, before being handed to the socket, and a stalled address is cached
 * bad for 10 minutes. IP literals (LAN `ws://192.168.x.x:8000`) skip all of
 * this.
 *
 * DoH trust is layered: platform store first (fails on old images — observed
 * Android 8.0.0 predates the current issuing roots), then PKIX against
 * platform + the bundled [DohRoots] anchors, then an unvalidated retry,
 * clearly logged — a MITM'd DNS answer is no worse than the resolver hijack
 * this class exists to escape. The relay socket itself is never relaxed.
 */
internal class DohFirstDns(private val context: android.content.Context) : Dns {

    private val strictClient: OkHttpClient by lazy { dohClient(DohTrustManager) }
    private val lenientClient: OkHttpClient by lazy { dohClient(TrustAll) }

    override fun lookup(hostname: String): List<InetAddress> {
        if (isIpLiteral(hostname)) return runCatching { Dns.SYSTEM.lookup(hostname) }.getOrNull() ?: emptyList()
        val system = runCatching { Dns.SYSTEM.lookup(hostname) }.getOrNull() ?: emptyList()
        val saved = SessionStore.goodIp(context, hostname)
            ?.let { runCatching { InetAddress.getByName(it) }.getOrNull() }

        // Fast path: the last address that actually completed a relay session
        // is usually still the right one. Verify/reuse it before paying for
        // DoH or probing every system-DNS candidate — on the observed home
        // network, dead DoH endpoints plus two poisoned system answers added
        // ~20s to an otherwise instant send.
        if (saved != null && savedIsUsable(hostname, saved)) {
            val result = listOf(saved) + system.filter { it.hostAddress != saved.hostAddress }
            Log.i(
                TAG,
                "$hostname -> ${result.map { a -> a.hostAddress }} " +
                    "(saved=${saved.hostAddress}, fast-path, system=${system.map { a -> a.hostAddress }})",
            )
            return result
        }

        val doh = dohLookup(hostname)
        val candidates = (listOfNotNull(saved) + (doh ?: emptyList()) + system)
            .distinct()
            .take(MAX_CANDIDATES)
        if (candidates.isEmpty()) return system
        val ordered = orderWithProbe(hostname, candidates)
        // Persist only a verified address: the fast path trusts this value to
        // be the last one that actually worked, and the KDoc promise only
        // holds when every candidate was probed dead and the first entry is
        // just an unverified backup.
        ordered.firstOrNull()?.hostAddress?.let { ip ->
            if (synchronized(badLock) { (goodUntil[ip] ?: 0L) > System.currentTimeMillis() }) {
                SessionStore.rememberGoodIp(context, hostname, ip)
            }
        }
        Log.i(
            TAG,
            "$hostname -> ${ordered.map { a -> a.hostAddress }} " +
                "(saved=${saved?.hostAddress ?: "-"}, doh=${doh?.map { a -> a.hostAddress } ?: "-"}, " +
                "system=${system.map { a -> a.hostAddress }})",
        )
        return ordered
    }

    /** The persisted last-good address is usable when it is not cached bad
     * and is either still inside the verified-good window or re-confirms with
     * a single TLS probe. One probe (<= 3 s) replaces the whole DoH fan-out
     * plus multi-candidate probing on the common path — on the observed home
     * network that is the difference between an instant reconnect and ~20 s
     * of dead endpoints and poisoned answers. */
    private fun savedIsUsable(hostname: String, saved: InetAddress): Boolean {
        val key = saved.hostAddress ?: return false
        val now = System.currentTimeMillis()
        if (synchronized(badLock) { badUntil[key] ?: 0L } > now) return false
        if (synchronized(badLock) { goodUntil[key] ?: 0L } > now) {
            Log.i(TAG, "$hostname $key saved address reused (verified, no probe)")
            return true
        }
        if (!tlsProbe(saved, hostname)) {
            synchronized(badLock) {
                badUntil[key] = now + BAD_CACHE_MS
                goodUntil.remove(key)
            }
            Log.w(TAG, "$hostname $key saved address failed TLS probe, re-resolving")
            return false
        }
        synchronized(badLock) { goodUntil[key] = System.currentTimeMillis() + GOOD_CACHE_MS }
        Log.i(TAG, "$hostname $key saved address re-verified, DoH skipped")
        return true
    }

    private fun isIpLiteral(host: String): Boolean {
        if (host.contains(':')) return true // IPv6, possibly bracketed
        val parts = host.split('.')
        return parts.size == 4 && parts.all { it.isNotEmpty() && it.all { c -> c in '0'..'9' } }
    }

    private fun dohClient(trustManager: X509TrustManager): OkHttpClient {
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf(trustManager), null)
        return OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .writeTimeout(2, TimeUnit.SECONDS)
            .sslSocketFactory(ctx.socketFactory, trustManager)
            // The platform default re-validates the chain against the system
            // store, which would defeat the bundled roots on old images.
            .hostnameVerifier(SanHostnameVerifier)
            .build()
    }

    private fun dohLookup(hostname: String): List<InetAddress>? {
        val now = System.currentTimeMillis()
        for ((base, path) in DOH_ENDPOINTS) {
            val dead = synchronized(badLock) { dohDeadUntil[base] ?: 0L }
            if (dead > now) {
                Log.w(TAG, "DoH $base skipped (dead ${(dead - now) / 1000}s more)")
                continue
            }
            try {
                val ans = dohQuery(hostname, base, path, strictClient)
                if (ans != null) {
                    synchronized(badLock) { dohDeadUntil.remove(base) }
                    return ans
                }
                // HTTP OK but no A records: the provider did answer (or a
                // poisoned proxy did) — try the next provider, no blacklist.
                Log.w(TAG, "DoH $base no A records")
            } catch (t: Throwable) {
                if (isTlsFailure(t)) {
                    // Old trust store (observed Android 8): one unvalidated
                    // try at this endpoint before giving up on it — a
                    // MITM'd answer is no worse than the poisoned resolver.
                    try {
                        val ans = dohQuery(hostname, base, path, lenientClient)
                        if (ans != null) {
                            Log.w(TAG, "DoH $base succeeded with UNVALIDATED trust")
                            synchronized(badLock) { dohDeadUntil.remove(base) }
                            return ans
                        }
                        Log.w(TAG, "DoH $base (lenient) no answer")
                    } catch (t2: Throwable) {
                        Log.w(TAG, "DoH $base (lenient) failed: ${t2.message}")
                    }
                } else {
                    Log.w(TAG, "DoH $base failed: ${t.message}", t)
                }
                // Network-level failure (RST, stall, blocked egress, sinkholed
                // endpoint host). On censoring networks these persist for
                // hours, so blacklisting spares every restart from the
                // per-endpoint stall timeouts.
                synchronized(badLock) { dohDeadUntil[base] = now + DOH_DEAD_MS }
                Log.w(TAG, "DoH $base dead for ${DOH_DEAD_MS / 60000}m")
            }
        }
        return null
    }

    private fun dohQuery(hostname: String, base: String, path: String, client: OkHttpClient): List<InetAddress>? {
        val url = URL("$base$path?name=${URLEncoder.encode(hostname, "UTF-8")}&type=A")
        val req = Request.Builder().url(url).header("accept", "application/dns-json").build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.w(TAG, "DoH $base http=${resp.code}")
                return null
            }
            val json = JSONObject(resp.body!!.string())
            val answers = json.optJSONArray("Answer") ?: return null
            return (0 until answers.length()).mapNotNull { i ->
                val a = answers.optJSONObject(i) ?: return@mapNotNull null
                // type 1 = A record; data is already an IP literal.
                if (a.optInt("type") != 1) return@mapNotNull null
                InetAddress.getByName(a.optString("data"))
            }
        }
    }

    /** Verifies candidates with a TLS handshake (real edge: ~hundreds of ms;
     * sinkhole: stalls past the 3 s budget) and returns verified ones first,
     * stopping after one verified candidate — the socket only needs one
     * honest address, and extra probes would just burn stall budgets.
     * Addresses already known bad within [BAD_CACHE_MS] are skipped without
     * probing; addresses verified within [GOOD_CACHE_MS] are reused without
     * re-probing (a stop/start cycle must not pay the probe cost again).
     * Unverified/bad addresses keep their relative order as backup — OkHttp
     * tries them in sequence if nothing verified is reachable. */
    private fun orderWithProbe(hostname: String, candidates: List<InetAddress>): List<InetAddress> {
        val good = mutableListOf<InetAddress>()
        val rest = mutableListOf<InetAddress>()
        val now = System.currentTimeMillis()
        for (ip in candidates) {
            val key = ip.hostAddress ?: continue
            val bad = synchronized(badLock) { badUntil[key] ?: 0L }
            if (bad > now) {
                rest += ip
                continue
            }
            if (good.isNotEmpty()) {
                rest += ip
                continue
            }
            val verified = synchronized(badLock) { goodUntil[key] ?: 0L }
            if (verified > now) {
                Log.i(TAG, "$hostname $key reused (verified, ${(verified - now) / 1000}s left)")
                good += ip
                continue
            }
            val started = System.currentTimeMillis()
            if (tlsProbe(ip, hostname)) {
                synchronized(badLock) { goodUntil[key] = System.currentTimeMillis() + GOOD_CACHE_MS }
                Log.i(TAG, "$hostname $key TLS probe ok (${System.currentTimeMillis() - started}ms)")
                good += ip
            } else {
                synchronized(badLock) {
                    badUntil[key] = now + BAD_CACHE_MS
                    goodUntil.remove(key)
                }
                Log.w(TAG, "$hostname $key failed TLS probe (${System.currentTimeMillis() - started}ms), cached bad ${BAD_CACHE_MS / 60000}m")
                rest += ip
            }
        }
        return good + rest
    }

    private fun tlsProbe(ip: InetAddress, hostname: String): Boolean {
        val pinned = object : Dns {
            override fun lookup(host: String): List<InetAddress> =
                if (host == hostname) listOf(ip) else Dns.SYSTEM.lookup(host)
        }
        // One-shot client pinned to the probed address with default trust:
        // the probe only needs to know whether TLS completes, and a real edge
        // serves a certificate the platform validates.
        val probe = OkHttpClient.Builder()
            .dns(pinned)
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .build()
        val req = Request.Builder().url("https://$hostname/").build()
        return try {
            probe.newCall(req).execute().use { true }
        } catch (t: Throwable) {
            false
        }
    }

    private fun isTlsFailure(t: Throwable): Boolean {
        var x: Throwable? = t
        while (x != null) {
            if (x is SSLException || x is CertificateException) return true
            x = x.cause
        }
        return false
    }

    companion object {
        private const val TAG = "RelayDns"
        private const val MAX_CANDIDATES = 4
        private const val BAD_CACHE_MS = 10 * 60_000L
        private const val GOOD_CACHE_MS = 5 * 60_000L
        private const val DOH_DEAD_MS = 10 * 60_000L
        // Process-wide: each RelaySocket builds its own DohFirstDns, and a
        // reconnect must not re-probe an address the previous socket just
        // proved dead (nor re-stall on a DoH endpoint the previous socket
        // just proved unreachable — on censoring networks that takes 3 s
        // each and they stay blocked for hours).
        private val badUntil = HashMap<String, Long>()
        private val goodUntil = HashMap<String, Long>()
        private val dohDeadUntil = HashMap<String, Long>()
        private val badLock = Any()
        // (base, path) pairs. IP literals first (the lookup cannot depend on
        // the poisoned resolver), then the domain forms for networks that
        // block egress to the IPs but allow the provider domains. Google
        // first: on Iranian networks Google egress is the most reliably open.
        private val DOH_ENDPOINTS = listOf(
            "https://8.8.8.8" to "/resolve",
            "https://8.8.4.4" to "/resolve",
            "https://1.1.1.1" to "/dns-query",
            "https://dns.google" to "/resolve",
            "https://cloudflare-dns.com" to "/dns-query",
        )
    }
}

/** Validates the chain via the platform store first, then PKIX against
 * platform anchors + the bundled [DohRoots]. Throws [CertificateException]
 * when neither accepts, so the caller can decide on the lenient retry. */
private object DohTrustManager : X509TrustManager {
    private val systemTms: X509TrustManager = run {
        val tfm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tfm.init(null as KeyStore?)
        tfm.trustManagers.filterIsInstance<X509TrustManager>().first()
    }
    private val certFactory: CertificateFactory = CertificateFactory.getInstance("X.509")
    private val bundledRoots: List<X509Certificate> by lazy {
        listOf(
            DohRoots.GTS_ROOT_R1_SELF,
            DohRoots.GTS_ROOT_R1_XSIGN,
            DohRoots.GTS_ROOT_R4_SELF,
            DohRoots.GTS_ROOT_R4_XSIGN,
            DohRoots.SSLCOM_ROOT_ECC,
        ).map { pem ->
            certFactory.generateCertificate(ByteArrayInputStream(pem.toByteArray())) as X509Certificate
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = systemTms.acceptedIssuers

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
        throw UnsupportedOperationException("DoH is server-auth only")
    }

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        if (chain.isEmpty()) throw IllegalArgumentException("empty cert chain")
        try {
            systemTms.checkServerTrusted(chain, authType)
            return
        } catch (e: CertificateException) {
            // Platform store is too old for the served chain; retry with the
            // bundled roots added to the anchor set.
        }
        val anchors = (systemTms.acceptedIssuers.toList() + bundledRoots)
            .map { TrustAnchor(it, null) }
            .toSet()
        val params = PKIXParameters(anchors)
        params.isRevocationEnabled = false
        val certs: List<Certificate> = chain.toList()
        CertPathValidator.getInstance("PKIX").validate(
            certFactory.generateCertPath(certs),
            params,
        )
    }
}

/** Trusts every chain; used only for the last-resort DoH retry. The SAN
 * hostname check (below) still applies, and the relay socket never uses this. */
private object TrustAll : X509TrustManager {
    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/** SAN-only hostname verification: the chain has already been validated by
 * whichever trust manager the client was built with. Accepts DNS names
 * (exact or one-level wildcard) and IP-address SANs. */
private object SanHostnameVerifier : HostnameVerifier {
    override fun verify(hostname: String, session: javax.net.ssl.SSLSession): Boolean {
        val leaf = session.peerCertificates.firstOrNull() as? X509Certificate ?: return false
        val sans = leaf.subjectAlternativeNames ?: return false
        val host = hostname.trim('[', ']')
        for (entry in sans) {
            val type = entry[0] as Int
            val value = entry[1] ?: continue
            if (type == 2 && value is String && dnsMatches(value, host)) return true
            if (type == 7 && ipMatches(value, host)) return true
        }
        return false
    }

    private fun dnsMatches(pattern: String, name: String): Boolean {
        if (pattern == name) return true
        if (!pattern.startsWith("*.")) return false
        val suffix = pattern.drop(2)
        val dot = name.indexOf('.')
        // One wildcard label: *.example.com matches a.example.com, not a.b.example.com.
        return dot > 0 && dot < name.length - 1 && name.substring(dot + 1) == suffix
    }

    // Conscrypt on some platforms (observed: Android 8) returns IP SANs as
    // dotted-quad strings; the JDK returns InetAddress. Accept both.
    private fun ipMatches(sanValue: Any, host: String): Boolean {
        val san: InetAddress = when (sanValue) {
            is InetAddress -> sanValue
            is String -> runCatching { InetAddress.getByName(sanValue) }.getOrNull() ?: return false
            else -> return false
        }
        val candidate = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return false
        return san.equals(candidate)
    }
}

/** "Samsung SM-A520F" / "Xiaomi 23049PCD8G" — advertised in the relay hello so
 *  the partner's UI can name the peer in its status pill. */
fun deviceName(): String {
    val m = Build.MANUFACTURER.trim()
    val model = Build.MODEL.trim()
    return if (model.isEmpty() || model.equals(m, ignoreCase = true)) m else "$m $model"
}
