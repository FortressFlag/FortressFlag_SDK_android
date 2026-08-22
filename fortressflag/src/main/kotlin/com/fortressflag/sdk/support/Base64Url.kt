package com.fortressflag.sdk.support

import java.util.Base64

/**
 * Unpadded base64url, the contract's only binary-to-text encoding (payloads, signatures,
 * the X-FF-Tags header, device-ID bodies).
 */
internal object Base64Url {
    fun encode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    fun decode(text: String): ByteArray? =
        try {
            Base64.getUrlDecoder().decode(text)
        } catch (_: IllegalArgumentException) {
            null
        }
}
