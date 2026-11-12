package com.vget.app.network


/** Keep the page title readable while leaving space for storage-provider suffixes. */
object DownloadFileNames {
    private val extensions = setOf("mp4", "webm", "mkv", "mp3")
    private val unsafe = Regex("[\\\\/:*?\"<>|]")
    private val controls = Regex("[\\p{Cc}\\u202a-\\u202e\\u2066-\\u2069]")
    private val whitespace = Regex("[\\p{Z}\\s]+")
    private val reserved = Regex("(?:CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])", RegexOption.IGNORE_CASE)

    fun fromTitle(title: String, extension: String, copyNumber: Int = 0): String {
        val ext = extension.lowercase()
        require(ext in extensions) { "不支援的下載檔案格式" }
        require(copyNumber >= 0)
        var stem = normalizeNfc(title)
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
            val first = value[end]
            val paired = first.isHighSurrogate() && end + 1 < value.length && value[end + 1].isLowSurrogate()
            val codePoint = if (paired) 0x10000 + ((first.code - 0xd800) shl 10) + (value[end + 1].code - 0xdc00)
                else first.code
            val count = when {
                codePoint <= 0x7f -> 1
                codePoint <= 0x7ff -> 2
                codePoint <= 0xffff -> 3
                else -> 4
            }
            if (bytes + count > maximumBytes) break
            bytes += count
            end += if (paired) 2 else 1
        }
        return value.substring(0, end)
    }
}
