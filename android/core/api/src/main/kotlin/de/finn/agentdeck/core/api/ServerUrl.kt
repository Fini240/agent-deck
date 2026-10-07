package de.finn.agentdeck.core.api

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URI
import java.net.URISyntaxException

/** Validated HTTPS base URL of a Mac helper. Plain HTTP is never accepted. */
@JvmInline
value class ServerUrl private constructor(val value: String) {
    val httpUrl: HttpUrl get() = value.toHttpUrlOrNull()!!
    val host: String get() = httpUrl.host

    override fun toString(): String = value

    companion object {
        /** Parses user or QR input. A missing scheme is treated as https; http is rejected. */
        fun parse(input: String): Result<ServerUrl> {
            val trimmed = input.trim()
            if (trimmed.isEmpty()) return Result.failure(InvalidServerUrl("Enter the server address."))
            val withScheme = when {
                trimmed.startsWith("https://", ignoreCase = true) -> trimmed
                trimmed.startsWith("http://", ignoreCase = true) ->
                    return Result.failure(InvalidServerUrl("Only HTTPS servers are allowed. Use the https:// address shown by the Mac helper."))
                trimmed.contains("://") -> return Result.failure(InvalidServerUrl("Only https:// addresses are supported."))
                else -> "https://$trimmed"
            }
            val url = withScheme.toHttpUrlOrNull()
                ?: return Result.failure(InvalidServerUrl("That is not a valid server address."))
            if (!url.isHttps) return Result.failure(InvalidServerUrl("Only HTTPS servers are allowed."))
            if (url.username.isNotEmpty() || url.password.isNotEmpty()) {
                return Result.failure(InvalidServerUrl("Server addresses must not contain credentials."))
            }
            if (url.query != null || url.fragment != null) {
                return Result.failure(InvalidServerUrl("Server address must not contain a query or fragment."))
            }
            val path = url.encodedPath.trimEnd('/')
            val normalized = url.newBuilder().encodedPath(if (path.isEmpty()) "/" else "$path/").build()
                .toString().trimEnd('/')
            return Result.success(ServerUrl(normalized))
        }

        /** Restores a value previously produced by [parse]. */
        fun trusted(value: String): ServerUrl = parse(value).getOrThrow()
    }
}

class InvalidServerUrl(message: String) : IllegalArgumentException(message)

/** Contents of an enrollment QR code: `agentdeck://pair?server=...&code=...`. */
data class PairingLink(val server: ServerUrl, val code: String) {
    companion object {
        fun parse(raw: String): Result<PairingLink> {
            val uri = try {
                URI(raw.trim())
            } catch (_: URISyntaxException) {
                return Result.failure(IllegalArgumentException("This QR code is not an Agent Deck pairing code."))
            }
            if (!uri.scheme.equals("agentdeck", ignoreCase = true) || !uri.host.equals("pair", ignoreCase = true)) {
                return Result.failure(IllegalArgumentException("This QR code is not an Agent Deck pairing code."))
            }
            val params = (uri.rawQuery ?: "").split('&').filter { it.isNotEmpty() }.associate { part ->
                val idx = part.indexOf('=')
                val k = if (idx < 0) part else part.substring(0, idx)
                val v = if (idx < 0) "" else part.substring(idx + 1)
                decode(k) to decode(v)
            }
            val server = params["server"] ?: return Result.failure(IllegalArgumentException("Pairing code is missing the server address."))
            val code = params["code"]?.trim().orEmpty()
            if (code.isEmpty()) return Result.failure(IllegalArgumentException("Pairing code is missing the one-time code."))
            return ServerUrl.parse(server).map { PairingLink(it, code) }
        }

        private fun decode(s: String) = java.net.URLDecoder.decode(s, Charsets.UTF_8)
    }
}
