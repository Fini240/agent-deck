package de.finn.agentdeck.ui

import android.content.SharedPreferences
import android.os.Build
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.finn.agentdeck.AppGraph
import de.finn.agentdeck.BuildConfig
import de.finn.agentdeck.core.api.AgentDeckClient
import de.finn.agentdeck.core.api.ApiException
import de.finn.agentdeck.core.api.PairingLink
import de.finn.agentdeck.core.api.ServerUrl
import de.finn.agentdeck.core.model.ActivityFilter
import de.finn.agentdeck.core.model.Agents
import de.finn.agentdeck.core.model.BrowserScope
import de.finn.agentdeck.core.model.ModelsResponse
import de.finn.agentdeck.core.model.PairRequest
import de.finn.agentdeck.core.model.SettingsPatch
import de.finn.agentdeck.core.model.StartSessionRequest
import de.finn.agentdeck.ui.newsession.ModelChoice
import de.finn.agentdeck.ui.newsession.NewSessionUi
import de.finn.agentdeck.ui.pairing.PairingUi
import de.finn.agentdeck.ui.sessions.SessionListUi
import de.finn.agentdeck.ui.settings.SettingsUi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.UUID

enum class Screen { HOME, NEW_SESSION, SETTINGS, PAIRING }

data class NavState(val screen: Screen, val selectedId: String? = null)

class MainViewModel(private val graph: AppGraph, private val uiPrefs: SharedPreferences) : ViewModel() {
    private val repo = graph.repository

    private val _nav = MutableStateFlow(NavState(if (graph.credentials.current() == null) Screen.PAIRING else Screen.HOME))
    val nav: StateFlow<NavState> = _nav.asStateFlow()

    /** Scope, filters and folder/child disclosure persist; the search text is transient (survives navigation and rotation, not a restart). */
    private data class ListPrefs(
        val filter: String,
        val activity: ActivityFilter,
        val expanded: Set<String>,
        val scope: BrowserScope,
        val collapsedFolders: Set<String>,
        val query: String = "",
    )
    private val listPrefs = MutableStateFlow(
        ListPrefs(
            uiPrefs.getString("filter", "all")?.takeIf { it == "all" || it == Agents.CLAUDE || it == Agents.CODEX } ?: "all",
            if (uiPrefs.getString("activity", "ALL") == "ACTIVE") ActivityFilter.ACTIVE else ActivityFilter.ALL,
            uiPrefs.getStringSet("expanded", emptySet()).orEmpty().toSet(),
            if (uiPrefs.getString("scope", "OPEN") == "ALL") BrowserScope.ALL else BrowserScope.OPEN,
            uiPrefs.getStringSet("collapsedFolders", emptySet()).orEmpty().toSet(),
        ),
    )

    val list: StateFlow<SessionListUi> = combine(repo.sessions, repo.connection, listPrefs, _nav, graph.credentials.credentials) { s, c, p, n, cr ->
        SessionListUi(
            sessions = s.sessions, filter = p.filter, activity = p.activity, expanded = p.expanded, connection = c,
            loaded = s.loaded, loading = s.loading, error = s.error, serverName = cr?.serverName.orEmpty(),
            selectedId = n.selectedId, now = Instant.now(),
            scope = p.scope, query = p.query, collapsedFolders = p.collapsedFolders,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SessionListUi())

    // ---- Navigation -------------------------------------------------------------------

    fun select(id: String?) = _nav.update { it.copy(screen = Screen.HOME, selectedId = id) }
    fun open(screen: Screen) {
        _nav.update { it.copy(screen = screen) }
        when (screen) {
            Screen.SETTINGS -> refreshSettingsScreen()
            Screen.NEW_SESSION -> prepareNewSession()
            Screen.PAIRING -> _pairing.update { it.copy(canGoBack = graph.credentials.current() != null, error = null) }
            Screen.HOME -> Unit
        }
    }

    /** Returns false when there is nothing left to go back to (let the system close the app). */
    fun back(wide: Boolean): Boolean {
        val n = _nav.value
        return when {
            n.screen == Screen.PAIRING && graph.credentials.current() == null -> false
            n.screen != Screen.HOME -> { _nav.update { it.copy(screen = Screen.HOME) }; true }
            !wide && n.selectedId != null -> { _nav.update { it.copy(selectedId = null) }; true }
            else -> false
        }
    }

    // ---- List ---------------------------------------------------------------------------

    fun refresh() = viewModelScope.launch { repo.refreshSessions() }

    fun setFilter(f: String) = savePrefs(listPrefs.value.copy(filter = f))
    fun setActivity(a: ActivityFilter) = savePrefs(listPrefs.value.copy(activity = a))
    fun setScope(s: BrowserScope) = savePrefs(listPrefs.value.copy(scope = s))
    fun toggleExpanded(id: String) {
        val cur = listPrefs.value.expanded
        savePrefs(listPrefs.value.copy(expanded = if (id in cur) cur - id else (cur + id).toList().takeLast(200).toSet()))
    }
    fun toggleFolder(key: String) {
        val cur = listPrefs.value.collapsedFolders
        savePrefs(listPrefs.value.copy(collapsedFolders = if (key in cur) cur - key else (cur + key).toList().takeLast(200).toSet()))
    }

    /** Search text is not written to disk. */
    fun setQuery(q: String) = listPrefs.update { it.copy(query = q.take(200)) }

    /** Provider and activity filters back to all; scope, search and disclosure stay. */
    fun clearFilters() = savePrefs(listPrefs.value.copy(filter = "all", activity = ActivityFilter.ALL))

    private fun savePrefs(p: ListPrefs) {
        listPrefs.value = p
        uiPrefs.edit {
            putString("filter", p.filter)
            putString("activity", p.activity.name)
            putStringSet("expanded", p.expanded)
            putString("scope", p.scope.name)
            putStringSet("collapsedFolders", p.collapsedFolders)
        }
    }

    // ---- Pairing ------------------------------------------------------------------------

    private val _pairing = MutableStateFlow(
        PairingUi(deviceName = listOf(Build.MANUFACTURER.replaceFirstChar { it.uppercase() }, Build.MODEL).distinct().joinToString(" ").trim(), notice = graph.credentials.loadError),
    )
    val pairing: StateFlow<PairingUi> = _pairing.asStateFlow()

    fun setPairServer(v: String) = _pairing.update { it.copy(server = v, error = null) }
    fun setPairCode(v: String) = _pairing.update { it.copy(code = v.trim(), error = null) }
    fun setPairName(v: String) = _pairing.update { it.copy(deviceName = v.take(80)) }

    /** QR result or `agentdeck://pair` deep link: fill in and pair right away. */
    fun onPairingLink(raw: String) {
        PairingLink.parse(raw).fold(
            onSuccess = { link ->
                _nav.update { it.copy(screen = Screen.PAIRING) }
                _pairing.update { it.copy(server = link.server.value, code = link.code, error = null, canGoBack = graph.credentials.current() != null) }
                pair()
            },
            onFailure = { e -> _pairing.update { it.copy(error = e.message) } },
        )
    }

    fun onScanError(message: String) = _pairing.update { it.copy(error = message) }

    fun pair() {
        val p = _pairing.value
        if (p.busy) return
        val server = ServerUrl.parse(p.server).getOrElse { e -> _pairing.update { it.copy(error = e.message) }; return }
        _pairing.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                val fcm = graph.push.tokenForPairing()
                val res = AgentDeckClient(server, { null }).pair(PairRequest(p.code, p.deviceName.ifBlank { "Android" }, fcm))
                graph.credentials.save(server, res.serverName, res.deviceId, res.token, res.pushKey)
                val creds = graph.credentials.current()!!
                if (fcm != null) graph.push.markRegisteredViaPairing(creds, fcm) else graph.push.forget()
                graph.onPaired()
                _pairing.update { PairingUi(deviceName = it.deviceName) }
                _nav.value = NavState(Screen.HOME)
            } catch (e: ApiException) {
                _pairing.update { it.copy(busy = false, error = e.message) }
            } catch (e: IllegalArgumentException) {
                _pairing.update { it.copy(busy = false, error = "The Mac sent invalid pairing data: ${e.message}") }
            }
        }
    }

    // ---- Settings -----------------------------------------------------------------------

    private val _settings = MutableStateFlow(SettingsUi(appVersion = BuildConfig.VERSION_NAME))
    val settings: StateFlow<SettingsUi> = combine(_settings, repo.connection, graph.push.status, graph.credentials.credentials) { s, c, push, cr ->
        s.copy(connection = c, push = push, serverName = cr?.serverName.orEmpty(), serverUrl = cr?.server?.value.orEmpty(), deviceId = cr?.deviceId.orEmpty())
    }.stateIn(viewModelScope, SharingStarted.Eagerly, _settings.value)

    fun refreshSettingsScreen() {
        graph.push.setPermissionGranted(graph.notifier.canPost())
        _settings.update {
            it.copy(
                permissionNeeded = !graph.notifier.canPost(),
                blockedChannels = graph.notifier.blockedChannels().map { c -> c.name.lowercase().replaceFirstChar(Char::uppercase) },
                statusLoading = true, statusError = null,
            )
        }
        viewModelScope.launch {
            try {
                val st = repo.call { status() }
                _settings.update { it.copy(serverStatus = st, statusLoading = false) }
            } catch (e: ApiException) {
                _settings.update { it.copy(statusError = e.message, statusLoading = false) }
            }
        }
        viewModelScope.launch {
            try {
                val s = repo.call { settings() }.settings
                _settings.update { it.copy(serverSettings = s) }
            } catch (_: ApiException) {
                // Shown via statusError from the parallel status call.
            }
        }
    }

    fun onPermissionResult() = refreshSettingsScreen()

    fun localTest() {
        val shown = graph.notifier.showLocalTest()
        _settings.update { it.copy(testMessage = if (shown) "Local notification posted by this phone." else "Android is blocking notifications for this app. Allow them first.") }
    }

    fun pushTest() {
        _settings.update { it.copy(testing = true, testMessage = null) }
        viewModelScope.launch {
            val msg = try {
                val r = repo.call { pushTest() }.result
                when {
                    r.ok > 0 -> "The Mac handed an encrypted test to Google's push service. It worked if a \"Encrypted test notification\" appears in a few seconds."
                    r.skipped > 0 -> "The Mac has no push token for this phone yet. Tap \"Register again\"."
                    r.unregistered > 0 -> "Google rejected this phone's token as expired. Tap \"Register again\"."
                    r.unavailable > 0 -> "The Mac's push sender is not configured."
                    else -> "The Mac could not send the test (errors: ${r.error})."
                }
            } catch (e: ApiException) {
                e.message ?: "Test failed."
            }
            _settings.update { it.copy(testing = false, testMessage = msg) }
            refreshSettingsScreen()
        }
    }

    fun registerPush() {
        val creds = graph.credentials.current() ?: return
        viewModelScope.launch {
            try {
                graph.push.ensureRegistered(repo, creds, force = true)
            } catch (_: ApiException) {
                // PushStatus carries the error.
            }
            refreshSettingsScreen()
        }
    }

    fun saveSettings(patch: SettingsPatch) {
        _settings.update { it.copy(settingsSaving = true, settingsMessage = null) }
        viewModelScope.launch {
            try {
                val s = repo.call { patchSettings(patch) }.settings
                _settings.update { it.copy(serverSettings = s, settingsSaving = false, settingsMessage = "Saved on the Mac.") }
            } catch (e: ApiException) {
                _settings.update { it.copy(settingsSaving = false, settingsMessage = "Not saved: ${e.message}") }
            }
        }
    }

    fun forget() {
        val creds = graph.credentials.current()
        viewModelScope.launch {
            val note = try {
                if (creds != null) repo.call { unpair(creds.deviceId) }
                null
            } catch (e: ApiException) {
                "Removed here. The Mac could not be told (${e.message}); revoke it there with agentdeck-admin.sh revoke."
            }
            graph.forgetPairing()
            _settings.value = SettingsUi(appVersion = BuildConfig.VERSION_NAME)
            _pairing.update { it.copy(notice = note, canGoBack = false) }
            _nav.value = NavState(Screen.PAIRING)
        }
    }

    // ---- New session --------------------------------------------------------------------

    private val _newSession = MutableStateFlow(NewSessionUi())
    val newSession: StateFlow<NewSessionUi> = _newSession.asStateFlow()
    private var startRequestId: String? = null

    private fun prepareNewSession() {
        _newSession.update { it.copy(error = null) }
        viewModelScope.launch { loadModels(refresh = false) }
        viewModelScope.launch {
            try {
                val s = repo.call { settings() }.settings
                _newSession.update { ui ->
                    val agent = s.defaultAgent ?: ui.agent
                    ui.copy(
                        workspaces = s.allowedWorkspaces, cwd = ui.cwd.ifBlank { s.allowedWorkspaces.firstOrNull().orEmpty() },
                        agent = agent, defaultModel = if (agent == Agents.CODEX) s.defaultModels.codex else s.defaultModels.claude,
                    )
                }
            } catch (_: ApiException) {
                // The folder can still be typed manually.
            }
        }
    }

    fun refreshModels() = viewModelScope.launch { loadModels(refresh = true) }

    private suspend fun loadModels(refresh: Boolean) {
        _newSession.update { it.copy(modelsLoading = true, modelsError = null) }
        try {
            val m: ModelsResponse = repo.call { if (refresh) refreshModels() else models() }
            _newSession.update { it.copy(agents = m.agents, modelsRefreshedAt = m.refreshedAt, modelsLoading = false) }
        } catch (e: ApiException) {
            _newSession.update { it.copy(modelsLoading = false, modelsError = "Couldn't load models: ${e.message} You can still type an exact model ID.") }
        }
    }

    fun setNewAgent(a: String) = _newSession.update { it.copy(agent = a, choice = ModelChoice.AgentDefault) }.also { startRequestId = null }
    fun setNewChoice(c: ModelChoice) = _newSession.update { it.copy(choice = c) }.also { startRequestId = null }
    fun setNewCustom(v: String) = _newSession.update { it.copy(customModel = v.trim()) }.also { startRequestId = null }
    fun setNewCwd(v: String) = _newSession.update { it.copy(cwd = v) }.also { startRequestId = null }
    fun setNewPrompt(v: String) = _newSession.update { it.copy(prompt = v) }.also { startRequestId = null }

    fun startSession() {
        val ui = _newSession.value
        if (!ui.canStart) return
        val rid = startRequestId ?: UUID.randomUUID().toString().also { startRequestId = it }
        _newSession.update { it.copy(starting = true, error = null) }
        viewModelScope.launch {
            try {
                val s = repo.call { startSession(StartSessionRequest(ui.agent, ui.modelToSend, ui.cwd.trim(), ui.prompt.trim(), rid)) }.session
                startRequestId = null
                _newSession.update { NewSessionUi(agents = it.agents, workspaces = it.workspaces, agent = it.agent, cwd = it.cwd, defaultModel = it.defaultModel) }
                repo.refreshSessions()
                _nav.value = NavState(Screen.HOME, s.id)
            } catch (e: ApiException) {
                _newSession.update { it.copy(starting = false, error = e.message) }
            }
        }
    }
}
