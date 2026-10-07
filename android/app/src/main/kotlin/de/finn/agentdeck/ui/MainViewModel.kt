package de.finn.agentdeck.ui

import android.content.SharedPreferences
import android.os.Build
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.finn.agentdeck.AppGraph
import de.finn.agentdeck.BuildConfig
import de.finn.agentdeck.data.PinnedChats
import de.finn.agentdeck.data.HostKeys
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
    private val pins = PinnedChats(uiPrefs)

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
        val pinnedOnly: Boolean = false,
        val pinRevision: Int = 0,
    )
    private fun loadPrefs(): ListPrefs {
        val prefix = graph.credentials.current()?.hostKey?.let { HostKeys.host(it) + ":" }.orEmpty()
        // Only the proven old Mac may inherit the old single-host list preferences.
        val useLegacy = uiPrefs.getString("legacy_list_host", null) == graph.credentials.current()?.hostKey
        fun k(name: String): String = if (useLegacy && !uiPrefs.contains(prefix + name)) name else prefix + name
        return ListPrefs(
            uiPrefs.getString(k("filter"), "all") ?: "all",
            ActivityFilter.entries.firstOrNull { it.name == uiPrefs.getString(k("activity"), "ALL") } ?: ActivityFilter.ALL,
            uiPrefs.getStringSet(k("expanded"), emptySet()).orEmpty().toSet(),
            if (uiPrefs.getString(k("scope"), "OPEN") == "ALL") BrowserScope.ALL else BrowserScope.OPEN,
            uiPrefs.getStringSet(k("collapsedFolders"), emptySet()).orEmpty().toSet(),
            pinnedOnly = uiPrefs.getBoolean(k("pinnedOnly"), false),
        )
    }
    private val listPrefs = MutableStateFlow(loadPrefs())

    val list: StateFlow<SessionListUi> = combine(repo.sessions, repo.connection, listPrefs, _nav, graph.credentials.credentials) { s, c, p, n, cr ->
        SessionListUi(
            sessions = s.sessions, filter = p.filter, activity = p.activity, expanded = p.expanded, connection = c,
            loaded = s.loaded, loading = s.loading, error = s.error, serverName = cr?.serverName.orEmpty(),
            selectedId = n.selectedId, now = Instant.now(),
            scope = p.scope, query = p.query, collapsedFolders = p.collapsedFolders,
            pinned = pins.get(cr?.server?.value), pinnedOnly = p.pinnedOnly,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SessionListUi())

    private var shownIdentity = graph.credentials.current()
    init {
        viewModelScope.launch {
            graph.credentials.credentials.collect { current ->
                if (shownIdentity != current) {
                    shownIdentity = current
                    _nav.value = NavState(if (current == null) Screen.PAIRING else Screen.HOME)
                    listPrefs.value = loadPrefs()
                    _settings.value = SettingsUi(appVersion = BuildConfig.VERSION_NAME)
                    _newSession.value = NewSessionUi()
                    startRequestId = null
                }
            }
        }
    }

    fun selectHost(key: String) {
        if (graph.selectHost(key)) {
            shownIdentity = graph.credentials.current()
            _nav.value = NavState(Screen.HOME)
            listPrefs.value = loadPrefs()
            _settings.value = SettingsUi(appVersion = BuildConfig.VERSION_NAME)
            _newSession.value = NewSessionUi()
            startRequestId = null
        }
    }

    fun renameHost(key: String, label: String) { graph.credentials.rename(key, label) }
    fun removeHost(key: String) { graph.removeHost(key) }

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
    fun setPinnedOnly(value: Boolean) = savePrefs(listPrefs.value.copy(pinnedOnly = value))
    fun togglePin(id: String) {
        pins.toggle(graph.credentials.current()?.server?.value, id)
        listPrefs.update { it.copy(pinRevision = it.pinRevision + 1) }
    }
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
    fun clearFilters() = savePrefs(listPrefs.value.copy(filter = "all", activity = ActivityFilter.ALL, pinnedOnly = false))

    private fun savePrefs(p: ListPrefs) {
        listPrefs.value = p
        val prefix = graph.credentials.current()?.hostKey?.let { HostKeys.host(it) + ":" }.orEmpty()
        uiPrefs.edit {
            putString(prefix + "filter", p.filter)
            putString(prefix + "activity", p.activity.name)
            putStringSet(prefix + "expanded", p.expanded)
            putString(prefix + "scope", p.scope.name)
            putStringSet(prefix + "collapsedFolders", p.collapsedFolders)
            putBoolean(prefix + "pinnedOnly", p.pinnedOnly)
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
        if (graph.credentials.host(server.value) == null && graph.credentials.hosts.value.size >= de.finn.agentdeck.data.CredentialStore.MAX_HOSTS) {
            _pairing.update { it.copy(error = "Remove a saved device before adding another (maximum 8).") }
            return
        }
        val previousPairing = graph.credentials.host(server.value)
        _pairing.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                val fcm = graph.push.tokenForPairing()
                val res = AgentDeckClient(server, { null }).pair(PairRequest(p.code, p.deviceName.ifBlank { "Android" }, fcm))
                previousPairing?.let { graph.notifier.cancelPairing(it.pairingKey) }
                graph.credentials.save(server, res.serverName, res.deviceId, res.token, res.pushKey)
                val creds = graph.credentials.current()!!
                if (fcm != null) graph.push.markRegisteredViaPairing(creds, fcm) else graph.push.forget(creds.pairingKey)
                graph.onPaired()
                _pairing.update { PairingUi(deviceName = it.deviceName) }
                _nav.value = NavState(Screen.HOME)
            } catch (e: ApiException) {
                _pairing.update { it.copy(busy = false, error = e.message) }
            } catch (e: IllegalStateException) {
                _pairing.update { it.copy(busy = false, error = e.message) }
            } catch (e: IllegalArgumentException) {
                _pairing.update { it.copy(busy = false, error = "The device sent invalid pairing data: ${e.message}") }
            }
        }
    }

    // ---- Settings -----------------------------------------------------------------------

    private val _settings = MutableStateFlow(SettingsUi(appVersion = BuildConfig.VERSION_NAME))
    val settings: StateFlow<SettingsUi> = combine(_settings, repo.connection, graph.push.status, graph.credentials.credentials) { s, c, push, cr ->
        s.copy(connection = c, push = push, serverName = cr?.serverName.orEmpty(), serverUrl = cr?.server?.value.orEmpty(), deviceId = cr?.deviceId.orEmpty())
    }.stateIn(viewModelScope, SharingStarted.Eagerly, _settings.value)

    fun refreshSettingsScreen() {
        val identity = graph.credentials.current() ?: return
        graph.updates.check()
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
                val st = repo.callFor(identity) { status() }
                _settings.update { it.copy(serverStatus = st, statusLoading = false) }
            } catch (e: ApiException) {
                if (!repo.isActive(identity)) return@launch
                _settings.update { it.copy(statusError = e.message, statusLoading = false) }
            }
        }
        viewModelScope.launch {
            try {
                val s = repo.callFor(identity) { settings() }.settings
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
        val identity = graph.credentials.current() ?: return
        _settings.update { it.copy(testing = true, testMessage = null) }
        viewModelScope.launch {
            val msg = try {
                val r = repo.callFor(identity) { pushTest() }.result
                when {
                    r.ok > 0 -> "The device handed an encrypted test to Google's push service. It worked if a \"Encrypted test notification\" appears in a few seconds."
                    r.skipped > 0 -> "The device has no push token for this phone yet. Tap \"Register again\"."
                    r.unregistered > 0 -> "Google rejected this phone's token as expired. Tap \"Register again\"."
                    r.unavailable > 0 -> "The device's push sender is not configured."
                    else -> "The device could not send the test (errors: ${r.error})."
                }
            } catch (e: ApiException) {
                e.message ?: "Test failed."
            }
            if (!repo.isActive(identity)) return@launch
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
            if (repo.isActive(creds)) refreshSettingsScreen()
        }
    }

    fun saveSettings(patch: SettingsPatch) {
        val identity = graph.credentials.current() ?: return
        _settings.update { it.copy(settingsSaving = true, settingsMessage = null) }
        viewModelScope.launch {
            try {
                val s = repo.callFor(identity) { patchSettings(patch) }.settings
                _settings.update { it.copy(serverSettings = s, settingsSaving = false, settingsMessage = "Saved on the device.") }
            } catch (e: ApiException) {
                if (!repo.isActive(identity)) return@launch
                _settings.update { it.copy(settingsSaving = false, settingsMessage = "Not saved: ${e.message}") }
            }
        }
    }

    fun forget() {
        val creds = graph.credentials.current()
        viewModelScope.launch {
            val note = try {
                if (creds != null) repo.callSaved(creds) { unpair(creds.deviceId) }
                null
            } catch (e: ApiException) {
                "Removed here. The device could not be told (${e.message}); revoke it there with agentdeck-admin.sh revoke."
            }
            if (creds != null) graph.removeHost(creds.hostKey, creds.deviceId)
            if (graph.credentials.current() != null) { _nav.value = NavState(Screen.HOME); return@launch }
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
        val identity = graph.credentials.current() ?: return
        _newSession.update { it.copy(error = null) }
        viewModelScope.launch { loadModels(refresh = false, identity = identity) }
        viewModelScope.launch {
            try {
                val s = repo.callFor(identity) { settings() }.settings
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

    fun refreshModels() {
        val identity = graph.credentials.current() ?: return
        viewModelScope.launch { loadModels(refresh = true, identity = identity) }
    }

    private suspend fun loadModels(refresh: Boolean, identity: de.finn.agentdeck.data.Credentials) {
        if (!repo.isActive(identity)) return
        _newSession.update { it.copy(modelsLoading = true, modelsError = null) }
        try {
            val m: ModelsResponse = repo.callFor(identity) { if (refresh) refreshModels() else models() }
            _newSession.update { it.copy(agents = m.agents, modelsRefreshedAt = m.refreshedAt, modelsLoading = false) }
        } catch (e: ApiException) {
            if (!repo.isActive(identity)) return
            _newSession.update { it.copy(modelsLoading = false, modelsError = "Couldn't load models: ${e.message} You can still type an exact model ID.") }
        }
    }

    fun setNewAgent(a: String) = _newSession.update { it.copy(agent = a, choice = ModelChoice.AgentDefault) }.also { startRequestId = null }
    fun setNewChoice(c: ModelChoice) = _newSession.update { it.copy(choice = c) }.also { startRequestId = null }
    fun setNewCustom(v: String) = _newSession.update { it.copy(customModel = v.trim()) }.also { startRequestId = null }
    fun setNewCwd(v: String) = _newSession.update { it.copy(cwd = v) }.also { startRequestId = null }
    fun setNewPrompt(v: String) = _newSession.update { it.copy(prompt = v) }.also { startRequestId = null }

    fun startSession() {
        val identity = graph.credentials.current() ?: return
        val ui = _newSession.value
        if (!ui.canStart) return
        val rid = startRequestId ?: UUID.randomUUID().toString().also { startRequestId = it }
        _newSession.update { it.copy(starting = true, error = null) }
        viewModelScope.launch {
            try {
                val s = repo.callFor(identity) { startSession(StartSessionRequest(ui.agent, ui.modelToSend, ui.cwd.trim(), ui.prompt.trim(), rid)) }.session
                startRequestId = null
                _newSession.update { NewSessionUi(agents = it.agents, workspaces = it.workspaces, agent = it.agent, cwd = it.cwd, defaultModel = it.defaultModel) }
                repo.refreshSessions()
                if (!repo.isActive(identity)) return@launch
                _nav.value = NavState(Screen.HOME, s.id)
            } catch (e: ApiException) {
                if (!repo.isActive(identity)) return@launch
                _newSession.update { it.copy(starting = false, error = e.message) }
            }
        }
    }
}
