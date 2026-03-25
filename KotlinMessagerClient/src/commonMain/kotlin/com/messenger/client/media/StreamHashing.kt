package com.messenger.client.media

expect fun sha256Base64(bytes: ByteArray): String

expect fun sha256Base64ForFile(path: String): String

expect fun crc32Hex(bytes: ByteArray): String
