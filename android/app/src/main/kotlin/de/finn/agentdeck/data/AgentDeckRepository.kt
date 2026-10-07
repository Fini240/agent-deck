package de.finn.agentdeck.data

import de.finn.agentdeck.core.api.AgentDeckClient
import de.finn.agentdeck.core.api.ApiException
import de.finn.agentdeck.core.api.EventStream
import de.finn.agentdeck.core.api.StreamSignal
import de.finn.agentdeck.core.model.ServerEvent
import de.finn.agentdeck.core.model.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient

/** How the app is currently talking to the Mac. Shown verbatim in the UI; never optimistic. */
sealed interface Connection {
    data object NotPaired : Connection
    data object Connecting : Connection
    /** SSE stream open: updates arrive as they happen. */
    data object Live : Connection
    /** Stream down; refreshing every [intervalSeconds] s and retrying the stream. */
    data class Polling(val reason: String, val intervalSeconds: Int) : Connection
    data class Unauthorized(val message: String) : Connection
    /** App in background: no stream; push notifications (if configured) take over. */
    data object Paused : Connection
}

data class SessionsState(
    val sessions: List<Session> = emptyList(),
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val refreshedAtMillis: Long? = null,
)

/**
 * Single source of truth for server state. Owns the live event stream while the app is in the
 * foreground and falls back to polling when the stream is unavailable.
 */
class AgentDeckRepository(
    private val credentials: StateFlow<Credentials?>,
    private val scope: CoroutineScope,
    private val http: OkHttpClient = AgentDeckClient.defaultHttpClient(),
    private val pollIntervalMillis: Long = 10_000,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Whether a pairing is still saved (active or not). Defaults to "is the active pairing". */
    private val isSaved: (Credentials) -> Boolean = { credentials.value == it },
) {
    private val _sessions = MutableStateFlow(SessionsState())
    val sessions: StateFlow<SessionsState> = _sessions.asStateFlow()

    private val _connection = MutableStateFlow<Connection>(if (credentials.value == null) Connection.NotPaired else Connection.Paused)
    val connection: StateFlow<Connection> = _connection.asStateFlow()

    /** Server events plus synthetic `poll` ticks while polling, so open screens refresh themselves. */
    private val _events = MutableSharedFlow<ServerEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<ServerEvent> = _events.asSharedFlow()

    private var liveJob: Job? = null
    private var cachedClient: Pair<Credentials, AgentDeckClient>? = null
    private var refreshLock = Mutex()

    fun client(): AgentDeckClient {
        val creds = credentials.value ?: throw ApiException.NotPaired()
        cachedClient?.let { (c, client) -> if (c == creds) return client }
        val client = AgentDeckClient(creds.server, {
            // A retained client must never send a new pairing's token to its old server.
            creds.token.takeIf { credentials.value == creds }
        }, http)
        cachedClient = creds to client
        return client
    }

    /** Runs [block] against the current client and records auth loss centrally. */
    suspend fun <T> call(block: suspend AgentDeckClient.() -> T): T {
        val identity = credentials.value ?: throw ApiException.NotPaired()
        return callFor(identity, block)
    }

    /** Binds delayed work to its original pairing, including across suspension points. */
    suspend fun <T> callFor(identity: Credentials, block: suspend AgentDeckClient.() -> T): T {
        if (credentials.value != identity) throw ApiException.HostChanged()
        val bound = AgentDeckClient(identity.server, { identity.token.takeIf { credentials.value == identity } }, http)
        return try {
            bound.block().also { if (credentials.value != identity) throw ApiException.HostChanged() }
        } catch (e: ApiException.Unauthorized) {
            if (credentials.value == identity) _connection.value = Connection.Unauthorized(e.message ?: "Pair again.")
            throw e
        }
    }

    /**
     * For background work bound to a saved pairing that may not be active (notification replies,
     * push registration): the token only leaves the phone while exactly that pairing is still saved,
     * and only towards its own host. Never touches the active host's connection state.
     */
    suspend fun <T> callSaved(identity: Credentials, block: suspend AgentDeckClient.() -> T): T {
        if (!isSaved(identity)) throw ApiException.NotPaired()
        val bound = AgentDeckClient(identity.server, { identity.token.takeIf { isSaved(identity) } }, http)
        return bound.block().also { if (!isSaved(identity)) throw ApiException.NotPaired() }
    }

    fun currentIdentity(): Credentials? = credentials.value

    /** True while [identity] is the active pairing. */
    fun isActive(identity: Credentials): Boolean = credentials.value == identity

    suspend fun refreshSessions() {
        if (credentials.value == null) {
            _sessions.value = SessionsState()
            _connection.value = Connection.NotPaired
            return
        }
        refreshLock.withLock {
            val identity = credentials.value ?: return@withLock
            _sessions.update { it.copy(loading = true) }
            try {
                val list = call { sessions() }.sessions
                if (credentials.value == identity) {
                    _sessions.value = SessionsState(sortSessions(list), loaded = true, loading = false, error = null, refreshedAtMillis = clock())
                }
            } catch (e: ApiException) {
                if (credentials.value == identity) _sessions.update { it.copy(loading = false, error = e.message) }
            }
        }
    }

    private var pendingRefresh: Job? = null

    /** Coalesces bursts of events (e.g. streamed messages) into one list refresh. */
    private fun requestRefresh() {
        if (pendingRefresh?.isActive == true) return
        pendingRefresh = scope.launch {
            delay(300)
            refreshSessions()
        }
    }

    /** Start the stream + polling fallback. Called when the app comes to the foreground. */
    fun startLive() {
        if (liveJob?.isActive == true) return
        if (credentials.value == null) {
            _connection.value = Connection.NotPaired
            return
        }
        liveJob = scope.launch {
            launch { pollWhileNotLive() }
            _connection.value = Connection.Connecting
            refreshSessions()
            EventStream(client()).signals().collect { signal ->
                when (signal) {
                    StreamSignal.Connecting -> if (_connection.value !is Connection.Polling) _connection.value = Connection.Connecting
                    StreamSignal.Connected -> {
                        _connection.value = Connection.Live
                        refreshSessions()
                        _events.tryEmit(ServerEvent(type = "reconnected"))
                    }
                    is StreamSignal.Update -> {
                        _events.tryEmit(signal.event)
                        if (signal.event.type in SESSION_LIST_EVENTS) requestRefresh()
                    }
                    is StreamSignal.Disconnected -> _connection.value = when {
                        signal.error is ApiException.Unauthorized -> Connection.Unauthorized(signal.error?.message ?: "Pair again.")
                        signal.fatal -> Connection.Polling(signal.error?.message ?: "Live updates unavailable", (pollIntervalMillis / 1000).toInt())
                        else -> Connection.Polling(signal.error?.message ?: "Live connection closed; reconnecting", (pollIntervalMillis / 1000).toInt())
                    }
                }
            }
        }
    }

    fun stopLive() {
        liveJob?.cancel()
        liveJob = null
        if (_connection.value !is Connection.Unauthorized) {
            _connection.value = if (credentials.value == null) Connection.NotPaired else Connection.Paused
        }
    }

    /** Re-evaluate after pairing changes. */
    fun onCredentialsChanged(foreground: Boolean) {
        stopLive()
        pendingRefresh?.cancel()
        pendingRefresh = null
        cachedClient = null
        refreshLock = Mutex()
        _sessions.value = SessionsState()
        _connection.value = if (credentials.value == null) Connection.NotPaired else Connection.Paused
        if (foreground) startLive()
    }

    private suspend fun pollWhileNotLive() {
        while (true) {
            delay(pollIntervalMillis)
            when (_connection.value) {
                is Connection.Polling, Connection.Connecting -> {
                    refreshSessions()
                    _events.tryEmit(ServerEvent(type = "poll"))
                }
                is Connection.Unauthorized -> return
                else -> Unit
            }
        }
    }

    companion object {
        private val SESSION_LIST_EVENTS = setOf("sessions", "approval", "notification", "messages")

        /** Needs attention first, then working, then most recent. */
        fun sortSessions(list: List<Session>): List<Session> = list.sortedWith(
            compareBy<Session> { priority(it) }.thenByDescending { it.updatedAt.orEmpty() },
        )

        private fun priority(s: Session) = when (s.status) {
            de.finn.agentdeck.core.model.SessionStatus.NEEDS_INPUT -> 0
            de.finn.agentdeck.core.model.SessionStatus.WORKING -> 1
            de.finn.agentdeck.core.model.SessionStatus.ERROR -> 2
            else -> 3
        }
    }
}
