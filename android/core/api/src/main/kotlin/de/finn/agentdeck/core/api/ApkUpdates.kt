package de.finn.agentdeck.core.api

import de.finn.agentdeck.core.model.AgentDeckJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/** Public metadata on the paired helper; never contains or sends device credentials. */
@Serializable
data class ApkOffer(
    val available: Boolean = false,
    val version: String? = null,
    val size: Long? = null,
    val sha256: String? = null,
    val url: String? = null,
) {
    fun validate() {
        require(available && AppVersion.parse(version) != null) { "The Mac did not report a valid update version." }
        require(size != null && size in 1..MAX_APK_BYTES) { "The update size is invalid." }
        require(sha256?.matches(Regex("[a-fA-F0-9]{64}")) == true) { "The update checksum is invalid." }
        require(url == "/apk") { "The update address is invalid." }
    }
    companion object { const val MAX_APK_BYTES = 150L * 1024 * 1024 }
}

data class AppVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<AppVersion> {
    override fun compareTo(other: AppVersion) = compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })
    companion object {
        fun parse(raw: String?): AppVersion? {
            if (raw == null || !raw.matches(Regex("[0-9]{1,9}\\.[0-9]{1,9}\\.[0-9]{1,9}"))) return null
            val p = raw.split('.').map { it.toInt() }
            return AppVersion(p[0], p[1], p[2])
        }
    }
}

interface ApkUpdateSource {
    suspend fun check(): ApkOffer?
    suspend fun download(offer: ApkOffer, destination: File, progress: (Long, Long) -> Unit)
}

class ApkUpdateClient(val server: ServerUrl, baseHttp: OkHttpClient = AgentDeckClient.defaultHttpClient()) : ApkUpdateSource {
    private val http = baseHttp.newBuilder().followRedirects(false).followSslRedirects(false)
        .callTimeout(5, TimeUnit.MINUTES).build()

    private fun request(path: String) = Request.Builder().url(server.httpUrl.newBuilder().addPathSegments(path).build())
        .header("Cache-Control", "no-cache").build()

    override suspend fun check(): ApkOffer? {
        val response = http.newCall(request("web/apk-info")).await()
        return response.use {
            withContext(Dispatchers.IO) {
                require(it.isSuccessful) { "Could not check for updates (${it.code}). Keep Tailscale connected and your Mac online." }
                val source = it.body.source()
                source.request(65537L)
                require(source.buffer.size <= 65536) { "The update information is too large." }
                val bytes = source.readByteArray()
                val offer = AgentDeckJson.decodeFromString(ApkOffer.serializer(), bytes.toString(Charsets.UTF_8))
                if (!offer.available) null else offer.also { o -> o.validate() }
            }
        }
    }

    /** A unique caller-owned temporary file. Any failure or cancellation removes it. */
    override suspend fun download(offer: ApkOffer, destination: File, progress: (Long, Long) -> Unit) {
        offer.validate()
        try {
            val response = http.newCall(request("apk")).await()
            response.use {
                withContext(Dispatchers.IO) {
                    require(it.isSuccessful) { "Could not download the update (${it.code}). Check the Mac connection and retry." }
                    val expected = offer.size!!
                    val length = it.body.contentLength()
                    require(length < 0 || length == expected) { "The update changed on the Mac. Check for updates again." }
                    val hash = MessageDigest.getInstance("SHA-256")
                    var read = 0L
                    it.body.byteStream().use { input ->
                        destination.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                coroutineContext.ensureActive()
                                val n = input.read(buffer)
                                if (n < 0) break
                                read += n
                                require(read <= expected) { "The downloaded update is larger than expected." }
                                output.write(buffer, 0, n)
                                hash.update(buffer, 0, n)
                                progress(read, expected)
                            }
                        }
                    }
                    require(read == expected) { "The download was incomplete. Retry the download." }
                    require(hash.digest().joinToString("") { b -> "%02x".format(b) }.equals(offer.sha256, ignoreCase = true)) {
                        "The update checksum did not match. Check for updates and try again."
                    }
                }
            }
        } catch (e: Throwable) {
            destination.delete()
            throw e
        }
    }
}
