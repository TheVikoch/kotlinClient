package com.messenger.client.media

expect fun base64Encode(bytes: ByteArray): String

expect fun base64Decode(value: String): ByteArray
