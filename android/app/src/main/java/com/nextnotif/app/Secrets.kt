package com.nextnotif.app

import android.util.Base64
import java.security.SecureRandom

object Secrets {
    /** 24 random bytes, base64url, unpadded (32 chars). Same shape as the server's device tokens. */
    fun generate(): String {
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(
            bytes,
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
        )
    }
}
