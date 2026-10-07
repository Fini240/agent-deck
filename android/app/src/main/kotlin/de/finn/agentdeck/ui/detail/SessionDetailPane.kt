package de.finn.agentdeck.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.finn.agentdeck.core.model.Agents
import de.finn.agentdeck.core.model.Approval
import de.finn.agentdeck.core.model.Message
import de.finn.agentdeck.core.model.Roles
import de.finn.agentdeck.core.model.Session
import de.finn.agentdeck.core.model.SessionStatus
import de.finn.agentdeck.core.model.TerminalKey
import de.finn.agentdeck.ui.components.Banner
import de.finn.agentdeck.ui.components.KeyValue
import de.finn.agentdeck.ui.components.ProgressLine
import de.finn.agentdeck.ui.components.StatusChip
import de.finn.agentdeck.ui.components.relativeTime
import de.finn.agentdeck.ui.components.shortPath
import kotlinx.coroutines.launch
import java.time.Instant

enum class DetailTab { CHAT, TERMINAL }

data class DetailUi(
    val session: Session? = null,
    val parent: Session? = null,
    val messages: List<Message> = emptyList(),
    val messagesLoaded: Boolean = false,
    val messagesError: String? = null,
    val approvals: List<Approval> = emptyList(),
    val approvalBusy: String? = null,
    val terminalText: String = "",
    val terminalAvailable: Boolean = false,
    val terminalLoaded: Boolean = false,
    val tab: DetailTab = DetailTab.CHAT,
    val draft: String = "",
    val sending: Boolean = false,
    val sendError: String? = null,
    val actionError: String? = null,
    val info: String? = null,
    val busyAction: String? = null,
    val now: Instant = Instant.now(),
)

data class DetailActions(
    val onBack: (() -> Unit)? = null,
    val onRefresh: () -> Unit = {},
    val onTab: (DetailTab) -> Unit = {},
    val onDraft: (String) -> Unit = {},
    val onSend: () -> Unit = {},
    val onStop: () -> Unit = {},
    val onResume: () -> Unit = {},
    val onApprove: (approvalId: String, choiceId: String) -> Unit = { _, _ -> },
    val onKey: (TerminalKey) -> Unit = {},
    val onOpenSession: (String) -> Unit = {},
    val onDismissMessage: () -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionDetailPane(ui: DetailUi, actions: DetailActions, modifier: Modifier = Modifier) {
    val s = ui.session
    // Keyed by session so a pending confirmation or open info panel never carries over to another chat.
    var confirmStop by rememberSaveable(s?.id) { mutableStateOf(false) }
    var showInfo by rememberSaveable(s?.id) { mutableStateOf(false) }
    val canStop = s != null && s.capabilities.stop && s.status == SessionStatus.WORKING
    Column(modifier.fillMaxSize().imePadding()) {
        TopAppBar(
            navigationIcon = {
                actions.onBack?.let { IconButton(onClick = it) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to sessions") } }
            },
            title = {
                Column {
                    Text(s?.let(::displayTitle) ?: "Session", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (s != null) {
                        Text(
                            listOfNotNull(Agents.displayName(s.agent), if (s.isSubagent) "child agent" else null).joinToString(" · "),
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            },
            actions = {
                if (canStop) {
                    TextButton(onClick = { confirmStop = true }, enabled = ui.busyAction == null) { Text("Stop") }
                }
                if (s != null) {
                    IconButton(
                        onClick = { showInfo = !showInfo },
                        modifier = Modifier.semantics { stateDescription = if (showInfo) "Shown" else "Hidden" },
                    ) { Icon(Icons.Default.Info, contentDescription = if (showInfo) "Hide session info" else "Session info") }
                }
                IconButton(onClick = actions.onRefresh) { Icon(Icons.Default.Refresh, contentDescription = "Refresh this session") }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        )
        if (s == null) {
            Text("Select a session to see its conversation.", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
        Header(s, ui.now)
        if (showInfo) SessionInfo(s, ui.parent)
        ui.actionError?.let { Banner(it, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), actionLabel = "Dismiss", onAction = actions.onDismissMessage) }
        ui.info?.let { Banner(it, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), isError = false, actionLabel = "OK", onAction = actions.onDismissMessage) }
        ui.approvals.forEach { ApprovalCard(it, busy = ui.approvalBusy == it.id, enabled = s.capabilities.approve, onChoice = { c -> actions.onApprove(it.id, c) }) }

        val showTerminalTab = !s.isSubagent
        if (showTerminalTab) {
            PrimaryTabRow(selectedTabIndex = ui.tab.ordinal, containerColor = MaterialTheme.colorScheme.background) {
                Tab(selected = ui.tab == DetailTab.CHAT, onClick = { actions.onTab(DetailTab.CHAT) }, text = { Text("Chat") })
                Tab(selected = ui.tab == DetailTab.TERMINAL, onClick = { actions.onTab(DetailTab.TERMINAL) }, text = { Text("Terminal") })
            }
        } else {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        Box(Modifier.weight(1f)) {
            key(s.id) {
                if (ui.tab == DetailTab.TERMINAL && showTerminalTab) TerminalView(ui, s, actions) else ChatView(ui, s)
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        key(s.id) { BottomArea(ui, s, actions) }
    }
    if (confirmStop && canStop) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text("Stop current work?") },
            text = { Text("Interrupts what the agent is doing right now. The conversation stays and you can send a follow-up.") },
            confirmButton = { TextButton(onClick = { confirmStop = false; actions.onStop() }) { Text("Stop") } },
            dismissButton = { TextButton(onClick = { confirmStop = false }) { Text("Cancel") } },
        )
    }
}

fun displayTitle(s: Session): String =
    (if (s.isSubagent) s.agentName?.takeIf { it.isNotBlank() } else null) ?: s.title.ifBlank { shortPath(s.cwd).ifBlank { s.id } }

/** One compact status line (plus real progress when reported); everything technical lives in [SessionInfo]. */
@Composable
private fun Header(s: Session, now: Instant) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusChip(s.status)
            s.stage?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            }
            Spacer(Modifier.weight(1f))
            Text(relativeTime(s.updatedAt, now), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        ProgressLine(s.progress, s.status)
        if (s.isSubagent) {
            s.task?.takeIf { it.isNotBlank() }?.let {
                Text("Task: $it", style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Explicit disclosure for metadata that used to eat cover-screen height; all of it stays selectable. */
@Composable
private fun SessionInfo(s: Session, parent: Session?) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        SelectionContainer {
            Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp)) {
                Text("Session info", style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
                KeyValue("Title", displayTitle(s))
                KeyValue("Provider", Agents.displayName(s.agent))
                s.model?.takeIf { it.isNotBlank() }?.let { KeyValue("Model", it) }
                if (s.cwd.isNotBlank()) KeyValue("Folder", s.cwd)
                s.projectRoot?.takeIf { it.isNotBlank() && it.trimEnd('/') != s.cwd.trimEnd('/') }?.let { KeyValue("Project", it) }
                KeyValue("Control", controlLabel(s))
                if (s.isSubagent) {
                    s.agentRole?.takeIf { it.isNotBlank() }?.let { KeyValue("Role", it) }
                    s.task?.takeIf { it.isNotBlank() }?.let { KeyValue("Task", it) }
                    parent?.let { KeyValue("Parent", displayTitle(it)) }
                }
                s.statusEvidence?.takeIf { it.isNotBlank() }?.let { KeyValue("Status from", it) }
                KeyValue("Session ID", s.id)
            }
        }
    }
}

private fun controlLabel(s: Session): String = when {
    s.isSubagent -> "Child agent, inspect-only"
    s.capabilities.send -> "Managed by Agent Deck"
    s.live == true -> "Open in a terminal Agent Deck doesn't control"
    s.canResume -> "Not running; can be resumed on the Mac"
    else -> "Read-only"
}

@Composable
private fun ApprovalCard(a: Approval, busy: Boolean, enabled: Boolean, onChoice: (String) -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(a.title.ifBlank { "Permission requested" }, style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
            if (a.detail.isNotBlank()) {
                Text(a.detail, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, maxLines = 8, overflow = TextOverflow.Ellipsis)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                a.choices.forEachIndexed { i, c ->
                    if (i == 0) {
                        Button(onClick = { onChoice(c.id) }, enabled = enabled && !busy) { Text(c.label) }
                    } else {
                        OutlinedButton(onClick = { onChoice(c.id) }, enabled = enabled && !busy) { Text(c.label) }
                    }
                }
            }
            if (busy) Text("Sending choice…", style = MaterialTheme.typography.labelSmall)
        }
    }
}

/**
 * Follows the newest message only while the reader is already at the bottom (or on first load).
 * Someone reading older messages stays put and gets a "Jump to latest" button instead.
 */
@Composable
private fun ChatView(ui: DetailUi, s: Session) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var follow by remember { mutableStateOf(true) }
    var unseen by remember { mutableStateOf(false) }
    // The content version whose layout we've already reacted to. Scroll position only updates
    // [follow] for that version, so the layout pass that inserts a new message can't be mistaken
    // for the reader scrolling away from the bottom.
    var handledKey by remember { mutableStateOf<String?>(null) }
    val contentKey = ui.messages.lastOrNull()?.let { "${ui.messages.size}:${it.id}:${it.text.hashCode()}" }
    val currentKey by rememberUpdatedState(contentKey)
    val anchorIndex = ui.messages.size // the 1 dp bottom anchor after the last message

    LaunchedEffect(contentKey) {
        if (contentKey == null) return@LaunchedEffect
        if (follow) listState.scrollToItem(anchorIndex) else unseen = true
        handledKey = contentKey
    }
    LaunchedEffect(listState) {
        snapshotFlow { Triple(!listState.canScrollForward, currentKey, handledKey) }.collect { (atBottom, cur, handled) ->
            if (cur != null && cur == handled) {
                follow = atBottom
                if (atBottom) unseen = false
            }
        }
    }

    when {
        ui.messagesError != null && ui.messages.isEmpty() -> Banner(ui.messagesError, Modifier.padding(16.dp))
        !ui.messagesLoaded -> Text("Loading conversation…", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        ui.messages.isEmpty() -> Text("No messages recorded yet.", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        else -> Box(Modifier.fillMaxSize()) {
            SelectionContainer {
                LazyColumn(state = listState, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(ui.messages, key = { it.id }) { MessageItem(it, s.agent, ui.now) }
                    item(key = BOTTOM_ANCHOR) { Spacer(Modifier.height(1.dp)) }
                }
            }
            if (!follow) {
                FilledTonalButton(
                    onClick = { scope.launch { listState.scrollToItem(anchorIndex) } },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp).heightIn(min = 48.dp),
                ) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text(if (unseen) "New messages" else "Jump to latest")
                }
            }
        }
    }
}

private const val BOTTOM_ANCHOR = "\u0000bottom"

fun roleLabel(m: Message, agent: String): String = when (m.role) {
    Roles.USER -> "You"
    Roles.ASSISTANT -> Agents.displayName(agent)
    Roles.TOOL -> "Tool" + (m.toolName?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
    else -> "System"
}

/** First meaningful line of tool output, shown while the output is collapsed. */
fun toolSummary(text: String): String = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: ""

@Composable
private fun MessageItem(m: Message, agent: String, now: Instant) {
    val (bg, mono) = when (m.role) {
        Roles.USER -> MaterialTheme.colorScheme.primaryContainer to false
        Roles.TOOL -> MaterialTheme.colorScheme.surfaceContainerHigh to true
        Roles.SYSTEM -> MaterialTheme.colorScheme.surfaceContainer to false
        else -> MaterialTheme.colorScheme.surface to false
    }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 4.dp, vertical = 2.dp)) {
            Text(roleLabel(m, agent), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text(relativeTime(m.timestamp, now), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Surface(color = bg, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
            if (m.role == Roles.TOOL && m.text.isNotBlank()) {
                ToolOutput(m)
            } else {
                Text(
                    m.text.ifBlank { "(empty)" },
                    style = if (mono) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                    fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
                    modifier = Modifier.padding(10.dp),
                )
            }
        }
    }
}

/** Collapsed to a one-line summary by default so tool calls don't drown the conversation. */
@Composable
private fun ToolOutput(m: Message) {
    var expanded by rememberSaveable(m.id) { mutableStateOf(false) }
    val lineCount = m.text.trimEnd().lines().size
    Column(Modifier.padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)) {
        if (expanded) {
            Text(m.text, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 6.dp, end = 6.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!expanded) {
                Text(
                    toolSummary(m.text), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            TextButton(onClick = { expanded = !expanded }) {
                Text(
                    when {
                        expanded -> "Hide output"
                        lineCount > 1 -> "Show output ($lineCount lines)"
                        else -> "Show output"
                    },
                )
            }
        }
    }
}

@Composable
private fun TerminalView(ui: DetailUi, s: Session, actions: DetailActions) {
    Column(Modifier.fillMaxSize()) {
        when {
            !s.managed -> Text(
                "The terminal view is only available for chats running through Agent Deck on the Mac.",
                Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            !ui.terminalLoaded -> Text("Loading terminal…", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            !ui.terminalAvailable -> Text("The Mac's terminal manager is not available right now.", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> Box(
                Modifier.weight(1f).fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLowest)
                    .verticalScroll(rememberScrollState(Int.MAX_VALUE)).horizontalScroll(rememberScrollState()).padding(12.dp),
            ) {
                SelectionContainer {
                    Text(ui.terminalText.takeLast(MAX_TERMINAL_CHARS).ifEmpty { "(empty)" }, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, softWrap = false)
                }
            }
        }
        if (s.managed && ui.terminalAvailable) {
            Text(
                "Fallback keys go straight to this session's terminal on the Mac.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp),
            )
            FlowRow(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(TerminalKey.UP to "↑", TerminalKey.DOWN to "↓", TerminalKey.ENTER to "Enter", TerminalKey.ESCAPE to "Esc", TerminalKey.TAB to "Tab").forEach { (k, label) ->
                    OutlinedButton(onClick = { actions.onKey(k) }, enabled = ui.busyAction == null, modifier = Modifier.widthIn(min = 56.dp)) {
                        Text(label, maxLines = 1)
                    }
                }
            }
        }
    }
}

private const val MAX_TERMINAL_CHARS = 64_000

@Composable
private fun BottomArea(ui: DetailUi, s: Session, actions: DetailActions) {
    val explain: @Composable (String, String?, (() -> Unit)?) -> Unit = { text, button, onClick ->
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (button != null && onClick != null) FilledTonalButton(onClick = onClick, enabled = ui.busyAction == null) { Text(button) }
            }
        }
    }
    when {
        s.isSubagent -> explain(
            ui.parent?.let { "Child agents are inspect-only. Send follow-ups to the parent chat “${displayTitle(it)}”." }
                ?: "Child agents are inspect-only. Its parent chat isn't in the current session list.",
            ui.parent?.let { "Open parent chat" }, ui.parent?.let { p -> { actions.onOpenSession(p.id) } },
        )
        s.capabilities.send -> Composer(ui, s, actions)
        s.live == true || s.status == SessionStatus.WORKING || s.status == SessionStatus.NEEDS_INPUT -> explain(
            "Read-only: open in a terminal Agent Deck doesn't control. Close it there, then resume it here to reply.", null, null,
        )
        s.canResume -> explain(
            "Read-only: not running through Agent Deck. Resume it in a managed terminal on the Mac to reply from here.",
            if (ui.busyAction == "resume") "Resuming…" else "Resume on the Mac", actions.onResume,
        )
        else -> explain("Read-only on this phone. The Mac helper doesn't offer controls for this chat.", null, null)
    }
}

/**
 * Full-width input with a slim action row. Sending always interrupts current work (contract), so
 * the row says so while the agent is working instead of a separate oversized button.
 */
@Composable
private fun Composer(ui: DetailUi, s: Session, actions: DetailActions) {
    val working = s.status == SessionStatus.WORKING
    val canSend = ui.draft.isNotBlank() && !ui.sending
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    var wasSending by remember { mutableStateOf(ui.sending) }
    LaunchedEffect(ui.sending) {
        // Hide the keyboard only after a send that really completed (sending true -> false, no error,
        // draft cleared). On failure the keyboard and the kept text stay where they are.
        if (wasSending && !ui.sending && ui.sendError == null && ui.draft.isEmpty()) {
            focus.clearFocus()
            keyboard?.hide()
        }
        wasSending = ui.sending
    }
    Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ui.sendError?.let { Banner("Not sent: $it Your text is kept.", actionLabel = "Retry", onAction = actions.onSend) }
        OutlinedTextField(
            value = ui.draft,
            onValueChange = actions.onDraft,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Message ${Agents.displayName(s.agent)}") },
            minLines = 1,
            maxLines = 4,
            enabled = !ui.sending,
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                when {
                    ui.sending -> "Sending…"
                    working -> "Interrupts current work"
                    else -> ""
                },
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, modifier = Modifier.weight(1f).padding(start = 4.dp, end = 8.dp),
            )
            Button(onClick = actions.onSend, enabled = canSend, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Send")
            }
        }
    }
}
