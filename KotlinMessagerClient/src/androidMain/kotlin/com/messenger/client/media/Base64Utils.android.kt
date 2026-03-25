package com.messenger.client.media

import android.util.Base64

actual fun base64Encode(bytes: ByteArray): String {
    return Base64.encodeToString(bytes, Base64.NO_WRAP)
}

actual fun base64Decode(value: String): ByteArray {
    return Base64.decode(value, Base64.DEFAULT)
}
