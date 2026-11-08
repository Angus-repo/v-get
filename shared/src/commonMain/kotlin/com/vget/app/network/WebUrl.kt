package com.vget.app.network

import io.ktor.http.*

/** Shared URL boundary. Ktor preserves encoded query bytes, including signed '+' and '%2F'. */
class WebUrl private constructor(private val value: Url) {
    val host: String get() = value.host.lowercase()
    val scheme: String get() = value.protocol.name
    val isHttps: Boolean get() = value.protocol == URLProtocol.HTTPS
    val port: Int get() = value.port
    val username: String get() = value.user.orEmpty()
    val password: String get() = value.password.orEmpty()
    val encodedPath: String get() = value.encodedPath
    val pathSegments: List<String> get() = value.encodedPath.split('/').drop(1).map { it.decodeURLPart() }
    fun queryParameter(name: String): String? = value.parameters[name]
    fun newBuilder() = Builder(value)
    fun resolve(location: String): WebUrl? = runCatching { URLBuilder(value).takeFrom(location).build().toString().toHttpUrlOrNull() }.getOrNull()
    override fun toString(): String = value.toString()

    class Builder internal constructor(value: Url) {
        private val builder = URLBuilder(value)
        fun scheme(value: String) = apply { builder.protocol = URLProtocol.createOrDefault(value) }
        fun port(value: Int) = apply { builder.port = value }
        fun host(value: String) = apply { builder.host = value }
        fun fragment(value: String?) = apply { builder.fragment = value.orEmpty() }
        fun query(value: String?) = apply {
            builder.encodedParameters.clear()
            if (value != null) builder.encodedParameters.appendAll(parseQueryString(value, decode = false))
            builder.trailingQuery = false
        }
        fun build(): WebUrl = WebUrl(builder.build())
    }

    companion object {
        fun String.toHttpUrlOrNull(): WebUrl? = runCatching {
            // Ktor also accepts relative/non-HTTP URLs; these must never cross our boundary.
            require(startsWith("https://", true) || startsWith("http://", true))
            require(none { it.isISOControl() } && '\\' !in this)
            val url = Url(this)
            require(url.host.isNotBlank() && url.host.none { it.isWhitespace() })
            require(url.port in 1..65535)
            WebUrl(URLBuilder(url).apply { host = host.lowercase() }.build())
        }.getOrNull()
    }
}
