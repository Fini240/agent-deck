package de.finn.agentdeck.core.api

import de.finn.agentdeck.core.model.AcceptedResponse
import de.finn.agentdeck.core.model.AgentDeckJson
import de.finn.agentdeck.core.model.ApprovalResponseRequest
import de.finn.agentdeck.core.model.ApprovalsResponse
import de.finn.agentdeck.core.model.ErrorEnvelope
import de.finn.agentdeck.core.model.FcmTokenRequest
import de.finn.agentdeck.core.model.HealthResponse
import de.finn.agentdeck.core.model.InputKeyRequest
import de.finn.agentdeck.core.model.MessagesResponse
import de.finn.agentdeck.core.model.ModelsResponse
import de.finn.agentdeck.core.model.PairRequest
import de.finn.agentdeck.core.model.PairResponse
import de.finn.agentdeck.core.model.PushTestResponse
import de.finn.agentdeck.core.model.ResumeRequest
import de.finn.agentdeck.core.model.ServerStatus
import de.finn.agentdeck.core.model.SendRequest
import de.finn.agentdeck.core.model.SessionResponse
import de.finn.agentdeck.core.model.SessionsResponse
import de.finn.agentdeck.core.model.SettingsPatch
import de.finn.agentdeck.core.model.SettingsResponse
import de.finn.agentdeck.core.model.StartSessionRequest
import de.finn.agentdeck.core.model.StopRequest
import de.finn.agentdeck.core.model.TerminalKey
import de.finn.agentdeck.core.model.TerminalResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Typed client for the Agent Deck REST contract (`/api/v1`).
 *
 * TLS uses the platform trust store only; there is deliberately no way to inject a trust-all
 * manager or hostname verifier.
 */
class AgentDeckClient(
    val server: ServerUrl,
    private val token: () -> String?,
    baseHttp: OkHttpClient = defaultHttpClient(),
) {
    private val http: OkHttpClient = baseHttp
    /** Sends and starts wait on the helper for real input readiness, so they get a longer budget. */
    private val slowHttp: OkHttpClient = baseHttp.newBuilder().readTimeout(90, TimeUnit.SECONDS).callTimeout(120, TimeUnit.SECONDS).build()

    private fun url(vararg segments: String, query: Map<String, String> = emptyMap()): HttpUrl {
        val b = server.httpUrl.newBuilder()
        segments.forEach { b.addPathSegments(it) }
        query.forEach { (k, v) -> b.addQueryParameter(k, v) }
        return b.build()
    }

    private fun api(vararg segments: String, query: Map<String, String> = emptyMap()) =
        url("api/v1", *segments, query = query)

    // ---- Public endpoints ------------------------------------------------------------

    suspend fun health(): HealthResponse = get(url("health"), HealthResponse.serializer(), auth = false)

    suspend fun pair(request: PairRequest): PairResponse =
        post(api("pair"), PairRequest.serializer(), request, PairResponse.serializer(), auth = false)

    // ---- Authenticated endpoints ------------------------------------------------------

    suspend fun sessions(agent: String = "all", scope: String = "all"): SessionsResponse =
        get(api("sessions", query = mapOf("agent" to agent, "scope" to scope)), SessionsResponse.serializer())

    suspend fun messages(sessionId: String, limit: Int = 100): MessagesResponse =
        get(sessionUrl(sessionId, "messages", query = mapOf("limit" to limit.toString())), MessagesResponse.serializer())

    suspend fun terminal(sessionId: String): TerminalResponse =
        get(sessionUrl(sessionId, "terminal"), TerminalResponse.serializer())

    suspend fun approvals(sessionId: String): ApprovalsResponse =
        get(sessionUrl(sessionId, "approvals"), ApprovalsResponse.serializer())

    suspend fun startSession(request: StartSessionRequest): SessionResponse =
        post(api("sessions"), StartSessionRequest.serializer(), request, SessionResponse.serializer(), slow = true)

    /** Follow-up to the same session. Always `interrupt = true` per contract; [requestId] makes retries idempotent. */
    suspend fun send(sessionId: String, text: String, requestId: String): AcceptedResponse =
        post(sessionUrl(sessionId, "send"), SendRequest.serializer(), SendRequest(text, true, requestId), AcceptedResponse.serializer(), slow = true)

    suspend fun stop(sessionId: String, requestId: String): AcceptedResponse =
        post(sessionUrl(sessionId, "stop"), StopRequest.serializer(), StopRequest(requestId), AcceptedResponse.serializer(), slow = true)

    suspend fun respondApproval(sessionId: String, approvalId: String, choiceId: String, requestId: String): AcceptedResponse =
        post(
            sessionUrl(sessionId, "approvals", approvalId),
            ApprovalResponseRequest.serializer(), ApprovalResponseRequest(choiceId, requestId), AcceptedResponse.serializer(), slow = true,
        )

    suspend fun inputKey(sessionId: String, key: TerminalKey): AcceptedResponse =
        post(sessionUrl(sessionId, "input"), InputKeyRequest.serializer(), InputKeyRequest(key.wire), AcceptedResponse.serializer())

    /** Converts a read-only discovered chat into a managed one. Server refuses live chats and subagents. */
    suspend fun resume(sessionId: String, requestId: String): SessionResponse =
        post(sessionUrl(sessionId, "resume"), ResumeRequest.serializer(), ResumeRequest(requestId), SessionResponse.serializer(), slow = true)

    suspend fun status(): ServerStatus = get(api("status"), ServerStatus.serializer())

    /** Asks the Mac to send a real encrypted FCM test to this device only. */
    suspend fun pushTest(): PushTestResponse = postEmpty(api("push", "test"), PushTestResponse.serializer(), slow = true)

    /** Removes this phone from the Mac (server-side revoke). */
    suspend fun unpair(deviceId: String) {
        val u = server.httpUrl.newBuilder().addPathSegments("api/v1/devices").addPathSegment(deviceId).build()
        execute<Unit>(authed(Request.Builder().url(u).delete()), null, http)
    }

    suspend fun models(): ModelsResponse = get(api("models"), ModelsResponse.serializer())

    suspend fun refreshModels(): ModelsResponse =
        postEmpty(api("models", "refresh"), ModelsResponse.serializer(), slow = true)

    suspend fun settings(): SettingsResponse = get(api("settings"), SettingsResponse.serializer())

    suspend fun patchSettings(patch: SettingsPatch): SettingsResponse =
        send("PATCH", api("settings"), AgentDeckJson.encodeToString(SettingsPatch.serializer(), patch), SettingsResponse.serializer())!!

    suspend fun updateFcmToken(deviceId: String, fcmToken: String) {
        val u = server.httpUrl.newBuilder().addPathSegments("api/v1/devices").addPathSegment(deviceId).addPathSegment("fcm-token").build()
        send<Unit>("PUT", u, AgentDeckJson.encodeToString(FcmTokenRequest.serializer(), FcmTokenRequest(fcmToken)), null)
    }

    /** Builds the SSE request for [EventStream]. */
    fun eventsRequest(): Request = authed(Request.Builder().url(api("events")).header("Accept", "text/event-stream").header("Cache-Control", "no-cache"))

    fun eventsHttpClient(): OkHttpClient = http.newBuilder().readTimeout(75, TimeUnit.SECONDS).callTimeout(0, TimeUnit.SECONDS).build()

    // ---- Plumbing -----------------------------------------------------------------------

    private fun sessionUrl(sessionId: String, vararg tail: String, query: Map<String, String> = emptyMap()): HttpUrl {
        val b = server.httpUrl.newBuilder().addPathSegments("api/v1/sessions").addPathSegment(sessionId)
        tail.forEach { b.addPathSegment(it) }
        query.forEach { (k, v) -> b.addQueryParameter(k, v) }
        return b.build()
    }

    private fun authed(builder: Request.Builder): Request {
        val t = token() ?: throw ApiException.NotPaired()
        return builder.header("Authorization", "Bearer $t").build()
    }

    private suspend fun <T> get(url: HttpUrl, out: KSerializer<T>, auth: Boolean = true): T {
        val b = Request.Builder().url(url).get().header("Accept", "application/json")
        return execute(if (auth) authed(b) else b.build(), out, http)!!
    }

    private suspend fun <I, T> post(url: HttpUrl, inSer: KSerializer<I>, body: I, out: KSerializer<T>, auth: Boolean = true, slow: Boolean = false): T {
        val b = Request.Builder().url(url).post(AgentDeckJson.encodeToString(inSer, body).toRequestBody(JSON)).header("Accept", "application/json")
        return execute(if (auth) authed(b) else b.build(), out, if (slow) slowHttp else http)!!
    }

    private suspend fun <T> postEmpty(url: HttpUrl, out: KSerializer<T>, slow: Boolean): T {
        val b = Request.Builder().url(url).post("{}".toRequestBody(JSON)).header("Accept", "application/json")
        return execute(authed(b), out, if (slow) slowHttp else http)!!
    }

    private suspend fun <T> send(method: String, url: HttpUrl, json: String, out: KSerializer<T>?): T? {
        val b = Request.Builder().url(url).method(method, json.toRequestBody(JSON)).header("Accept", "application/json")
        return execute(authed(b), out, http)
    }

    private suspend fun <T> execute(request: Request, out: KSerializer<T>?, client: OkHttpClient): T? {
        val response = try {
            client.newCall(request).await()
        } catch (e: SSLException) {
            throw ApiException.Tls(e)
        } catch (e: IOException) {
            throw ApiException.Network(e)
        }
        return withContext(Dispatchers.IO) {
            response.use { r ->
                val text = try {
                    r.body.string()
                } catch (e: IOException) {
                    throw ApiException.Network(e)
                }
                if (!r.isSuccessful) throw errorFor(r, text)
                if (out == null) return@use null
                try {
                    AgentDeckJson.decodeFromString(out, text)
                } catch (e: SerializationException) {
                    throw ApiException.Protocol("The Mac helper sent a response this app doesn't understand. Update the app or helper.", e)
                } catch (e: IllegalArgumentException) {
                    throw ApiException.Protocol("The Mac helper sent a response this app doesn't understand. Update the app or helper.", e)
                }
            }
        }
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false) // POSTs are retried explicitly with the same requestId instead
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

        internal fun errorFor(r: Response, body: String): ApiException {
            val env = try {
                AgentDeckJson.decodeFromString(ErrorEnvelope.serializer(), body).error
            } catch (_: Exception) {
                null
            }
            val message = env?.message?.takeIf { it.isNotBlank() } ?: defaultMessage(r.code)
            return if (r.code == 401) {
                ApiException.Unauthorized(env?.message?.takeIf { it.isNotBlank() } ?: "The Mac no longer accepts this phone. Pair again.")
            } else {
                ApiException.Http(r.code, env?.code ?: "http_${r.code}", message)
            }
        }

        private fun defaultMessage(code: Int) = when (code) {
            400 -> "The Mac rejected the request."
            403 -> "This phone is not allowed to do that."
            404 -> "Not found on the Mac. It may have been closed."
            409 -> "The session changed in the meantime. Refresh and try again."
            410 -> "That request is no longer valid."
            429 -> "Too many attempts. Wait a moment and try again."
            in 500..599 -> "The Mac helper had an internal error ($code)."
            else -> "Request failed ($code)."
        }
    }
}

internal suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) = cont.resume(response) { _, r, _ -> r.close() }
        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }
    })
    cont.invokeOnCancellation { cancel() }
}
