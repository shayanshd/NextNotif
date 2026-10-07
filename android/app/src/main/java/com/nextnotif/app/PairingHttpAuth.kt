package com.nextnotif.app

import java.net.HttpURLConnection
import okhttp3.Request

/** Carries a secure device identity when present; legacy pairings use tokens alone. */
internal fun Request.Builder.pairingAuth(pairing: PairingInfo): Request.Builder = apply {
    pairing.deviceId?.let { header("X-NextNotif-Device-Id", it) }
    pairing.deviceToken?.let { header("X-NextNotif-Token", it) }
}

internal fun HttpURLConnection.pairingAuth(pairing: PairingInfo) {
    pairing.deviceId?.let { setRequestProperty("X-NextNotif-Device-Id", it) }
    pairing.deviceToken?.let { setRequestProperty("X-NextNotif-Token", it) }
}
