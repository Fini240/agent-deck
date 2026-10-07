package de.finn.agentdeck.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
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
import de.finn.agentdeck.ui.components.ProgressLine
import de.finn.agentdeck.ui.components.StatusChip
import de.finn.agentdeck.ui.components.relativeTime
import de.finn.agentdeck.ui.components.shortPath
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
    var confirmStop by remember { mutableStateOf(false) }
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
                            listOfNotNull(Agents.displayName(s.agent), s.model, if (s.isSubagent) "child agent" else null).joinToString(" · "),
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            },
            actions = {
                if (s != null && s.capabilities.stop && s.status == SessionStatus.WORKING) {
                    TextButton(onClick = { confirmStop = true }, enabled = ui.busyAction == null) { Text("Stop") }
                }
                IconButton(onClick = actions.onRefresh) { Icon(Icons.Default.Refresh, contentDescription = "Refresh this session") }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        )
        if (s == null) {
            Text("Select a session to see its conversation.", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
        Header(s, ui.parent, ui.now, actions)
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
            if (ui.tab == DetailTab.TERMINAL && showTerminalTab) TerminalView(ui, s, actions) else ChatView(ui, s)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        BottomArea(ui, s, actions)
    }
    if (confirmStop) {
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

@Composable
private fun Header(s: Session, parent: Session?, now: Instant, actions: DetailActions) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
            s.agentRole?.takeIf { it.isNotBlank() }?.let { Text("Role: $it", style = MaterialTheme.typography.bodySmall) }
            s.task?.takeIf { it.isNotBlank() }?.let {
                Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp)) {
                        Text("Task", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 6, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (parent != null) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClickLabel = "Open parent chat") { actions.onOpenSession(parent.id) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Parent: ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(displayTitle(parent), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        } else if (s.cwd.isNotBlank()) {
            Text(shortPath(s.cwd), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
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

@Composable
private fun ChatView(ui: DetailUi, s: Session) {
    val listState = rememberLazyListState()
    LaunchedEffect(ui.messages.size) { if (ui.messages.isNotEmpty()) listState.scrollToItem(ui.messages.size - 1) }
    when {
        ui.messagesError != null && ui.messages.isEmpty() -> Banner(ui.messagesError, Modifier.padding(16.dp))
        !ui.messagesLoaded -> Text("Loading conversation…", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        ui.messages.isEmpty() -> Text("No messages recorded yet.", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        else -> SelectionContainer {
            LazyColumn(state = listState, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(ui.messages, key = { it.id }) { MessageItem(it, s.agent, ui.now) }
            }
        }
    }
}

fun roleLabel(m: Message, agent: String): String = when (m.role) {
    Roles.USER -> "You"
    Roles.ASSISTANT -> Agents.displayName(agent)
    Roles.TOOL -> "Tool" + (m.toolName?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
    else -> "System"
}

@Composable
private fun MessageItem(m: Message, agent: String, now: Instant) {
    var expanded by rememberSaveable(m.id) { mutableStateOf(false) }
    val (bg, mono) = when (m.role) {
        Roles.USER -> MaterialTheme.colorScheme.primaryContainer to false
        Roles.TOOL -> MaterialTheme.colorScheme.surfaceContainerHigh to true
        Roles.SYSTEM -> MaterialTheme.colorScheme.surfaceContainer to false
        else -> MaterialTheme.colorScheme.surface to false
    }
    val collapsible = m.role == Roles.TOOL && m.text.lines().size > 8
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 4.dp, vertical = 2.dp)) {
            Text(roleLabel(m, agent), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text(relativeTime(m.timestamp, now), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Surface(color = bg, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(10.dp)) {
                Text(
                    m.text.ifBlank { "(empty)" },
                    style = if (mono) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                    fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
                    maxLines = if (collapsible && !expanded) 8 else Int.MAX_VALUE,
                    overflow = TextOverflow.Ellipsis,
                )
                if (collapsible) {
                    TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Show less" else "Show all output") }
                }
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
                        Text(label, modifier = Modifier.semantics { }, maxLines = 1)
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
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (button != null && onClick != null) OutlinedButton(onClick = onClick, enabled = ui.busyAction == null) { Text(button) }
        }
    }
    when {
        s.isSubagent -> explain(
            "Child agents are inspect-only. They can't be steered directly — send your follow-up to the parent chat.",
            ui.parent?.let { "Open parent chat" }, ui.parent?.let { p -> { actions.onOpenSession(p.id) } },
        )
        s.capabilities.send -> Composer(ui, s, actions)
        s.live == true || s.status == SessionStatus.WORKING || s.status == SessionStatus.NEEDS_INPUT -> explain(
            "This chat is open in a terminal that Agent Deck doesn't control. Close it there, then resume it here to reply from the phone.", null, null,
        )
        s.canResume -> explain(
            "Read-only: this chat was found on the Mac but isn't running through Agent Deck. Resuming it once moves it into a managed terminal so you can reply.",
            if (ui.busyAction == "resume") "Resuming…" else "Resume on the Mac", actions.onResume,
        )
        else -> explain("Read-only on this phone. The Mac helper doesn't offer controls for this chat.", null, null)
    }
}

@Composable
private fun Composer(ui: DetailUi, s: Session, actions: DetailActions) {
    val working = s.status == SessionStatus.WORKING
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ui.sendError?.let { Banner("Not sent: $it Your text is kept.", actionLabel = "Retry", onAction = actions.onSend) }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = ui.draft,
                onValueChange = actions.onDraft,
                modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                placeholder = { Text(if (working) "Follow-up (interrupts current turn)" else "Message ${Agents.displayName(s.agent)}") },
                maxLines = 6,
                enabled = !ui.sending,
            )
            if (working) {
                Button(onClick = actions.onSend, enabled = ui.draft.isNotBlank() && !ui.sending, modifier = Modifier.heightIn(min = 56.dp)) {
                    Text(if (ui.sending) "Sending…" else "Interrupt\n& send", style = MaterialTheme.typography.labelMedium)
                }
            } else {
                IconButton(onClick = actions.onSend, enabled = ui.draft.isNotBlank() && !ui.sending, modifier = Modifier.heightIn(min = 56.dp)) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = if (ui.sending) "Sending" else "Send", tint = if (ui.draft.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
                }
            }
        }
    }
}
