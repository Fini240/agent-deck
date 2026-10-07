package de.finn.agentdeck.core.api

import java.io.IOException
import javax.net.ssl.SSLException

/** Every client failure is one of these, with a message that can be shown to the user as-is. */
sealed class ApiException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** Server answered with an error envelope `{error:{code,message}}` or a bare status. */
    class Http(val status: Int, val code: String, message: String) : ApiException(message)

    /** Token rejected: the device was removed on the Mac or the credentials are gone. */
    class Unauthorized(message: String) : ApiException(message)

    /** TLS failure. Never bypassed; the user must fix the server certificate. */
    class Tls(cause: SSLException) : ApiException(
        "Secure connection failed. The server certificate was not trusted, so nothing was sent.", cause,
    )

    class Network(cause: IOException) : ApiException(
        "Can't reach the Mac. Check that Tailscale is connected on this phone and the helper is running.", cause,
    )

    class Protocol(message: String, cause: Throwable? = null) : ApiException(message, cause)

    class NotPaired : ApiException("This phone is not paired with a Mac yet.")

    /** True when retrying the same request later may succeed. */
    val isTransient: Boolean
        get() = this is Network || (this is Http && (status == 408 || status == 429 || status >= 500))
}
