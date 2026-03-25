package com.messenger.client.media

import java.util.Base64

actual fun base64Encode(bytes: ByteArray): String {
    return Base64.getEncoder().encodeToString(bytes)
}

actual fun base64Decode(value: String): ByteArray {
    return Base64.getDecoder().decode(value)
}
