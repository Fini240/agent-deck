package de.finn.agentdeck.core.api

import de.finn.agentdeck.core.model.AgentDeckJson
import de.finn.agentdeck.core.model.ServerEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.job
import java.io.IOException
import javax.net.ssl.SSLException
import kotlin.random.Random

data class SseEvent(val event: String, val data: String, val id: String?)

/** Incremental `text/event-stream` parser (WHATWG rules for the fields the helper uses). */
class SseParser {
    private var event: String? = null
    private val data = StringBuilder()
    private var hasData = false
    private var id: String? = null

    /** Feed one line without its terminator. Returns an event when a blank line dispatches one. */
    fun feed(line: String): SseEvent? {
        if (line.isEmpty()) {
            val result = if (hasData) SseEvent(event ?: "message", data.toString(), id) else null
            event = null; data.setLength(0); hasData = false
            return result
        }
        if (line.startsWith(":")) return null // comment / heartbeat
        val colon = line.indexOf(':')
        val field = if (colon < 0) line else line.substring(0, colon)
        var value = if (colon < 0) "" else line.substring(colon + 1)
        if (value.startsWith(" ")) value = value.substring(1)
        when (field) {
            "event" -> event = value
            "data" -> {
                if (hasData) data.append('\n')
                data.append(value); hasData = true
            }
            "id" -> if (!value.contains('\u0000')) id = value
        }
        return null
    }
}

sealed interface StreamSignal {
    data object Connecting : StreamSignal
    data object Connected : StreamSignal
    data class Update(val event: ServerEvent) : StreamSignal
    /** Stream dropped; the flow reconnects by itself after [retryInMillis] unless [fatal]. */
    data class Disconnected(val error: ApiException?, val retryInMillis: Long, val fatal: Boolean) : StreamSignal
}

object Backoff {
    private const val BASE_MS = 1_000L
    private const val MAX_MS = 30_000L

    /** Exponential backoff with ±20 % jitter, capped at 30 s. [attempt] starts at 0. */
    fun delayFor(attempt: Int, random: Random = Random.Default): Long {
        val exp = BASE_MS shl attempt.coerceIn(0, 5)
        val capped = exp.coerceAtMost(MAX_MS)
        val jitter = (capped * 0.2 * (random.nextDouble() * 2 - 1)).toLong()
        return (capped + jitter).coerceIn(BASE_MS / 2, MAX_MS)
    }
}

/**
 * Live updates via `GET /api/v1/events`. Reconnects forever with backoff until the collector is
 * cancelled; an auth failure is fatal so the UI can ask to pair again instead of hammering.
 */
class EventStream(private val client: AgentDeckClient) {
    fun signals(): Flow<StreamSignal> = flow {
        val http = client.eventsHttpClient()
        var attempt = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            emit(StreamSignal.Connecting)
            var failure: ApiException? = null
            var fatal = false
            try {
                val call = http.newCall(client.eventsRequest())
                val handle = currentCoroutineContext().job.invokeOnCompletion { call.cancel() }
                try {
                    call.execute().use { response ->
                        if (!response.isSuccessful) {
                            val err = AgentDeckClient.errorFor(response, runCatching { response.body.string() }.getOrDefault(""))
                            failure = err
                            fatal = err is ApiException.Unauthorized || (err is ApiException.Http && err.status in 400..499 && err.status != 408 && err.status != 429)
                            return@use
                        }
                        emit(StreamSignal.Connected)
                        attempt = 0
                        val parser = SseParser()
                        val source = response.body.source()
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val line = source.readUtf8Line() ?: break
                            val ev = parser.feed(line) ?: continue
                            if (ev.event != "update" && ev.event != "message") continue
                            val parsed = runCatching { AgentDeckJson.decodeFromString(ServerEvent.serializer(), ev.data) }.getOrNull()
                            if (parsed != null) emit(StreamSignal.Update(parsed))
                        }
                    }
                } finally {
                    handle.dispose()
                }
            } catch (e: ApiException) {
                failure = e
                fatal = e is ApiException.NotPaired
            } catch (e: SSLException) {
                failure = ApiException.Tls(e)
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive()
                failure = ApiException.Network(e)
            }
            val wait = Backoff.delayFor(attempt++)
            emit(StreamSignal.Disconnected(failure, wait, fatal))
            if (fatal) return@flow
            delay(wait)
        }
    }.flowOn(Dispatchers.IO)
}
