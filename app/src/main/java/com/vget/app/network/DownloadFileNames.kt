package com.vget.app.network

import java.text.Normalizer
import java.util.Locale

/** Keep the page title readable while leaving space for storage-provider suffixes. */
internal object DownloadFileNames {
    private val extensions = setOf("mp4", "webm", "mkv", "mp3")
    private val unsafe = Regex("[\\\\/:*?\"<>|]")
    private val controls = Regex("[\\p{Cc}\\u202a-\\u202e\\u2066-\\u2069]")
    private val whitespace = Regex("[\\p{Z}\\s]+")
    private val reserved = Regex("(?:CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])", RegexOption.IGNORE_CASE)

    fun fromTitle(title: String, extension: String, copyNumber: Int = 0): String {
        val ext = extension.lowercase(Locale.ROOT)
        require(ext in extensions) { "不支援的下載檔案格式" }
        require(copyNumber >= 0)
        var stem = Normalizer.normalize(title, Normalizer.Form.NFC)
            .replace(controls, " ").replace(unsafe, "_").replace(whitespace, " ")
            .trim { it.isWhitespace() || it == '.' }
        if (stem.endsWith(".$ext", ignoreCase = true)) stem = stem.dropLast(ext.length + 1)
        stem = stem.trim { it.isWhitespace() || it == '.' }
        if (stem.isBlank() || stem.all { it == '_' }) stem = "V-Get"
        if (reserved.matches(stem.substringBefore('.'))) stem = "_$stem"
        stem = truncateUtf8(stem, 180).trimEnd(' ', '.')
        val suffix = if (copyNumber == 0) "" else " ($copyNumber)"
        return "$stem$suffix.$ext"
    }

    private fun truncateUtf8(value: String, maximumBytes: Int): String {
        var end = 0
        var bytes = 0
        while (end < value.length) {
            val codePoint = value.codePointAt(end)
            val count = when {
                codePoint <= 0x7f -> 1
                codePoint <= 0x7ff -> 2
                codePoint <= 0xffff -> 3
                else -> 4
            }
            if (bytes + count > maximumBytes) break
            bytes += count
            end += Character.charCount(codePoint)
        }
        return value.substring(0, end)
    }
}
