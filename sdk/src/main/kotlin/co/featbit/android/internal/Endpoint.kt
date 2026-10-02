package co.featbit.android.internal

import java.net.URI
import java.util.Locale

/** Shared endpoint policy. Normalize the scheme without decoding or lowercasing the path. */
internal class Endpoint private constructor(private val uri: URI, private val scheme: String) {
    val httpBase: String get() = when (scheme) {
        "ws" -> "http"
        "wss" -> "https"
        else -> scheme
    } + ":" + uri.rawSchemeSpecificPart

    // Keep existing namespace boundaries: explicit port and raw deployment path are significant.
    val namespace: String get() = scheme + "://" + uri.host.lowercase(Locale.ROOT) + ":" + uri.port + uri.rawPath

    companion object {
        fun parse(raw: String?, streaming: Boolean): Endpoint? = try {
            val uri = URI(raw ?: "")
            val scheme = uri.scheme?.lowercase(Locale.ROOT)
            val allowed = if (streaming) setOf("ws", "wss") else setOf("http", "https")
            if (scheme !in allowed || uri.host.isNullOrEmpty() || uri.userInfo != null || uri.query != null || uri.fragment != null) null
            else Endpoint(uri, scheme!!)
        } catch (_: Exception) { null }
    }
}
