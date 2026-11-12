package com.vget.app.network

import platform.Foundation.NSString
import platform.Foundation.precomposedStringWithCanonicalMapping

@Suppress("CAST_NEVER_SUCCEEDS")
internal actual fun normalizeNfc(value: String): String =
    (value as NSString).precomposedStringWithCanonicalMapping
