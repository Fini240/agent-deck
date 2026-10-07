package de.finn.agentdeck.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import de.finn.agentdeck.AppGraph
import de.finn.agentdeck.ui.detail.DetailActions
import de.finn.agentdeck.ui.detail.DetailViewModel
import de.finn.agentdeck.ui.detail.SessionDetailPane
import de.finn.agentdeck.ui.newsession.NewSessionActions
import de.finn.agentdeck.ui.newsession.NewSessionScreen
import de.finn.agentdeck.ui.pairing.PairingActions
import de.finn.agentdeck.ui.pairing.PairingScreen
import de.finn.agentdeck.ui.sessions.SessionListPane
import de.finn.agentdeck.ui.settings.SettingsActions
import de.finn.agentdeck.ui.settings.SettingsScreen
import de.finn.agentdeck.ui.settings.UpdatePrompt
import de.finn.agentdeck.update.UpdatePhase

/** Width at which the Fold's inner screen (and tablets) switch to list + detail side by side. */
val TwoPaneMinWidth: Dp = 600.dp

/**
 * Cover screen / phones: one pane at a time. Inner screen: list on the left, detail on the right.
 * Stateless so screenshot tests can render it with fixture panes.
 */
@Composable
fun AdaptiveHome(
    wide: Boolean,
    totalWidth: Dp,
    showDetail: Boolean,
    list: @Composable () -> Unit,
    detail: @Composable () -> Unit,
) {
    if (wide) {
        // 600dp inner screen: 320 list + 280 detail; 768: 320 + 448; 1440: 420 + 1020.
        val listWidth = (totalWidth * 0.4f).coerceIn(320.dp, 420.dp)
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.width(listWidth).fillMaxHeight()) { list() }
            VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Box(Modifier.weight(1f).fillMaxHeight()) {
                if (showDetail) {
                    detail()
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Select a chat to read and reply, or an agent to inspect it.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(24.dp))
                    }
                }
            }
        }
    } else if (showDetail) {
        detail()
    } else {
        list()
    }
}

@Composable
fun AppRoot(graph: AppGraph, vm: MainViewModel, onScan: () -> Unit, onRequestPermission: () -> Unit, onOpenNotificationSettings: () -> Unit, onInstallUpdate: () -> Unit = {}) {
    val hosts by graph.credentials.hosts.collectAsStateWithLifecycle()
    val active by graph.credentials.active.collectAsStateWithLifecycle()
    val identity by graph.credentials.credentials.collectAsStateWithLifecycle()
    val nav by vm.nav.collectAsStateWithLifecycle()
    val update by graph.updates.state.collectAsStateWithLifecycle()
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()) {
        val wide = maxWidth >= TwoPaneMinWidth
        val windowWidth = maxWidth
        BackHandler(enabled = nav.screen != Screen.HOME || (!wide && nav.selectedId != null)) { vm.back(wide) }
        when (nav.screen) {
            Screen.PAIRING -> {
                val ui by vm.pairing.collectAsStateWithLifecycle()
                PairingScreen(ui, PairingActions(vm::setPairServer, vm::setPairCode, vm::setPairName, onScan, vm::pair, { vm.back(wide) }))
            }
            Screen.SETTINGS -> {
                val ui by vm.settings.collectAsStateWithLifecycle()
                SettingsScreen(
                    ui.copy(update = update),
                    SettingsActions(
                        onCheckUpdate = { graph.updates.check(force = true) }, onDownloadUpdate = graph.updates::download, onInstallUpdate = onInstallUpdate,
                        onBack = { vm.back(wide) }, onRefreshStatus = vm::refreshSettingsScreen, onRequestPermission = onRequestPermission,
                        onOpenNotificationSettings = onOpenNotificationSettings, onLocalTest = vm::localTest, onPushTest = vm::pushTest,
                        onRegisterPush = vm::registerPush, onSaveSettings = vm::saveSettings, onRePair = { vm.open(Screen.PAIRING) }, onForget = vm::forget,
                    ),
                )
            }
            Screen.NEW_SESSION -> {
                val ui by vm.newSession.collectAsStateWithLifecycle()
                NewSessionScreen(
                    ui,
                    NewSessionActions(
                        onBack = { vm.back(wide) }, onAgent = vm::setNewAgent, onChoice = vm::setNewChoice, onCustom = vm::setNewCustom,
                        onCwd = vm::setNewCwd, onPrompt = vm::setNewPrompt, onRefreshModels = { vm.refreshModels() }, onStart = vm::startSession,
                    ),
                )
            }
            Screen.HOME -> {
                val list by vm.list.collectAsStateWithLifecycle()
                Column(Modifier.fillMaxSize()) {
                    HostPicker(hosts, active, vm::selectHost, { vm.open(Screen.PAIRING) }, vm::renameHost, vm::removeHost)
                    if (update.updateAvailable) {
                        TextButton(onClick = { vm.open(Screen.SETTINGS) }, modifier = Modifier) {
                            Text("App update ${update.offer?.version.orEmpty()} available · View update")
                        }
                    }
                    Box(Modifier.weight(1f)) {
                        AdaptiveHome(
                            wide = wide, totalWidth = windowWidth, showDetail = nav.selectedId != null,
                            list = {
                                SessionListPane(
                                    list, onFilter = vm::setFilter, onActivity = vm::setActivity, onToggleExpand = vm::toggleExpanded,
                                    onSelect = { vm.select(it) }, onRefresh = { vm.refresh() }, onNew = { vm.open(Screen.NEW_SESSION) },
                                    onSettings = { vm.open(Screen.SETTINGS) }, onRePair = { vm.open(Screen.PAIRING) },
                                    onScope = vm::setScope, onQuery = vm::setQuery, onToggleFolder = vm::toggleFolder, onClearFilters = vm::clearFilters,
                                    onPin = vm::togglePin, onPinnedOnly = vm::setPinnedOnly,
                                )
                            },
                            detail = { nav.selectedId?.let { DetailRoute(graph, it, if (wide) null else ({ vm.back(wide) }), onOpen = { id -> vm.select(id) }) } },
                        )
                    }
                }
            }
        }
    }
    if (nav.screen == Screen.HOME && update.showPrompt) {
        UpdatePrompt(update, onLater = graph.updates::dismissPrompt, onUpdateNow = {
            graph.updates.dismissPrompt()
            vm.open(Screen.SETTINGS)
            if (update.phase == UpdatePhase.READY) onInstallUpdate() else graph.updates.download()
        })
    }
}

@Composable
private fun DetailRoute(graph: AppGraph, sessionId: String, onBack: (() -> Unit)?, onOpen: (String) -> Unit) {
    val dvm: DetailViewModel = viewModel(
        key = "detail:${graph.credentials.current()?.pairingKey}:$sessionId",
        factory = viewModelFactory { initializer { DetailViewModel(sessionId, graph.repository, graph.drafts, graph.credentials.current()) } },
    )
    val ui by dvm.ui.collectAsStateWithLifecycle()
    SessionDetailPane(
        ui,
        DetailActions(
            onBack = onBack, onRefresh = dvm::refresh, onTab = dvm::setTab, onDraft = dvm::setDraft, onSend = dvm::send,
            onStop = dvm::stop, onResume = dvm::resume, onApprove = dvm::approve, onKey = dvm::key,
            onOpenSession = onOpen, onDismissMessage = dvm::dismissMessage,
        ),
    )
}
