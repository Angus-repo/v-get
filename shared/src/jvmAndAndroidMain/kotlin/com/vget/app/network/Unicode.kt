package com.vget.app.network

internal actual fun normalizeNfc(value: String): String =
    java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFC)
