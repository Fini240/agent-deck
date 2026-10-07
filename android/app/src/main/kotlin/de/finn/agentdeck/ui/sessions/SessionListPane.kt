package de.finn.agentdeck.ui.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.finn.agentdeck.core.model.ActivityFilter
import de.finn.agentdeck.core.model.AgentRow
import de.finn.agentdeck.core.model.Agents
import de.finn.agentdeck.core.model.BrowserQuery
import de.finn.agentdeck.core.model.BrowserResult
import de.finn.agentdeck.core.model.BrowserScope
import de.finn.agentdeck.core.model.ChatRow
import de.finn.agentdeck.core.model.FolderRow
import de.finn.agentdeck.core.model.ProjectGroup
import de.finn.agentdeck.core.model.Session
import de.finn.agentdeck.core.model.SessionBrowser
import de.finn.agentdeck.core.model.SessionGrouping
import de.finn.agentdeck.data.Connection
import de.finn.agentdeck.ui.components.Banner
import de.finn.agentdeck.ui.components.ProgressLine
import de.finn.agentdeck.ui.components.StatusChip
import de.finn.agentdeck.ui.components.relativeTime
import de.finn.agentdeck.ui.components.shortPath
import de.finn.agentdeck.ui.components.statusLabel
import de.finn.agentdeck.ui.theme.LocalStatusColors
import java.time.Instant

data class SessionListUi(
    val sessions: List<Session> = emptyList(),
    /** Agent filter: all | claude | codex. */
    val filter: String = "all",
    val activity: ActivityFilter = ActivityFilter.ALL,
    val expanded: Set<String> = emptySet(),
    val connection: Connection = Connection.Connecting,
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val serverName: String = "",
    val selectedId: String? = null,
    val now: Instant = Instant.now(),
    val scope: BrowserScope = BrowserScope.OPEN,
    /** Transient search text; matched trimmed and case-insensitive. */
    val query: String = "",
    val collapsedFolders: Set<String> = emptySet(),
) {
    val groups: List<ProjectGroup> get() = SessionGrouping.group(sessions, activity, filter)
    val browserQuery: BrowserQuery get() = BrowserQuery(scope, filter, activity, query)
    val browser: BrowserResult by lazy { SessionBrowser.browse(sessions, browserQuery, expanded, collapsedFolders) }
}

/** Room under the last row so the New chat button never covers it. */
private val FabClearance = 88.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionListPane(
    ui: SessionListUi,
    onFilter: (String) -> Unit,
    onActivity: (ActivityFilter) -> Unit,
    onToggleExpand: (String) -> Unit,
    onSelect: (String) -> Unit,
    onRefresh: () -> Unit,
    onNew: () -> Unit,
    onSettings: () -> Unit,
    onRePair: () -> Unit,
    modifier: Modifier = Modifier,
    onScope: (BrowserScope) -> Unit = {},
    onQuery: (String) -> Unit = {},
    onToggleFolder: (String) -> Unit = {},
    onClearFilters: () -> Unit = { onFilter("all"); onActivity(ActivityFilter.ALL) },
) {
    val result = ui.browser
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val openChat: (String) -> Unit = { id -> focus.clearFocus(); keyboard?.hide(); onSelect(id) }
    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TopAppBar(
                title = {
                    Column {
                        Text("Agent Deck", style = MaterialTheme.typography.titleLarge)
                        if (ui.serverName.isNotBlank()) {
                            Text(ui.serverName, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, contentDescription = "Refresh chats") }
                    IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, contentDescription = "Settings and connection") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
            ConnectionLine(ui.connection, onRePair)
            SearchField(ui.query, onQuery)
            FilterChips(ui, onScope, onFilter, onActivity)
            if (ui.error != null && ui.connection !is Connection.Unauthorized) {
                Banner(ui.error, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), actionLabel = "Retry", onAction = onRefresh)
            }
            PullToRefreshBox(isRefreshing = ui.loading && ui.loaded, onRefresh = onRefresh, modifier = Modifier.weight(1f)) {
                when {
                    !ui.loaded && ui.error == null -> EmptyState("Loading chats…")
                    ui.loaded && ui.sessions.isEmpty() -> EmptyState("No chats on this Mac yet. Start Claude Code or Codex in a terminal, or tap New chat to start one here.")
                    ui.loaded && result.isEmpty -> NoResults(ui, result, onQuery, onScope, onClearFilters)
                    else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = FabClearance)) {
                        item(key = "summary") { Summary(ui, result, onScope) }
                        result.rows.forEach { row ->
                            when (row) {
                                is FolderRow -> item(key = row.key) {
                                    FolderHeader(row, searching = ui.browserQuery.searching, onToggle = { onToggleFolder(row.folder) })
                                }
                                is ChatRow -> {
                                    item(key = row.key) {
                                        SessionRow(
                                            row.session, selected = row.session.id == ui.selectedId, now = ui.now,
                                            onClick = { openChat(row.session.id) },
                                            childCount = row.childCount, activeChildren = row.activeChildren,
                                            expanded = row.expanded || row.revealedBySearch, onToggle = { onToggleExpand(row.session.id) },
                                            context = row.context, revealedBySearch = row.revealedBySearch, scope = ui.scope,
                                        )
                                    }
                                }
                                is AgentRow -> item(key = row.key) {
                                    ChildRow(row.session, row.depth, selected = row.session.id == ui.selectedId, now = ui.now, onClick = { openChat(row.session.id) }, context = row.context)
                                }
                            }
                        }
                    }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = onNew,
            icon = { Icon(Icons.Default.Add, contentDescription = null) },
            text = { Text("New chat") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).semantics { contentDescription = "New chat" },
        )
    }
}

@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit) {
    val focus = LocalFocusManager.current
    OutlinedTextField(
        value = query,
        onValueChange = onQuery,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
            .semantics { contentDescription = "Search chats, agents and folders" },
        singleLine = true,
        placeholder = { Text("Search chats, agents, folders", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = if (query.isNotEmpty()) {
            { IconButton(onClick = { onQuery("") }) { Icon(Icons.Default.Clear, contentDescription = "Clear search") } }
        } else {
            null
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
        shape = RoundedCornerShape(28.dp),
    )
}

/** Wraps instead of scrolling sideways, so nothing is clipped at large text sizes. */
@Composable
private fun FilterChips(ui: SessionListUi, onScope: (BrowserScope) -> Unit, onFilter: (String) -> Unit, onActivity: (ActivityFilter) -> Unit) {
    var providerMenu by remember { mutableStateOf(false) }
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(selected = ui.scope == BrowserScope.OPEN, onClick = { onScope(BrowserScope.OPEN) }, label = { Text("Open chats") })
        FilterChip(selected = ui.scope == BrowserScope.ALL, onClick = { onScope(BrowserScope.ALL) }, label = { Text("All chats") })
        Box {
            FilterChip(
                selected = ui.filter != "all", onClick = { providerMenu = true },
                label = { Text(if (ui.filter == "all") "All providers" else Agents.displayName(ui.filter)) },
                trailingIcon = { Icon(Icons.Default.KeyboardArrowDown, contentDescription = null) },
            )
            DropdownMenu(expanded = providerMenu, onDismissRequest = { providerMenu = false }) {
                listOf("all" to "All providers", Agents.CLAUDE to "Claude", Agents.CODEX to "Codex").forEach { (id, label) ->
                    DropdownMenuItem(text = { Text(label) }, onClick = { onFilter(id); providerMenu = false })
                }
            }
        }
        // Kept for people who saved it; shown only while set so it can be turned off.
        if (ui.activity == ActivityFilter.ACTIVE) {
            FilterChip(
                selected = true, onClick = { onActivity(ActivityFilter.ALL) },
                label = { Text("Working or waiting only") },
                trailingIcon = { Icon(Icons.Default.Clear, contentDescription = null, modifier = Modifier.size(18.dp)) },
                modifier = Modifier.semantics { contentDescription = "Remove filter: working or waiting only" },
            )
        }
    }
}

private fun plural(n: Int, one: String, many: String) = "$n ${if (n == 1) one else many}"

private fun countLabel(chats: Int, agents: Int): String =
    listOfNotNull(if (chats > 0 || agents == 0) plural(chats, "chat", "chats") else null, if (agents > 0) plural(agents, "child agent", "child agents") else null).joinToString(" · ")

@Composable
private fun Summary(ui: SessionListUi, r: BrowserResult, onScope: (BrowserScope) -> Unit) {
    val what = when {
        ui.browserQuery.searching -> "Matches: "
        ui.scope == BrowserScope.OPEN -> "Open: "
        else -> "All: "
    }
    FlowRow(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            what + countLabel(r.chats, r.childAgents),
            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        if (ui.scope == BrowserScope.OPEN && r.hiddenByScope > 0) {
            TextButton(onClick = { onScope(BrowserScope.ALL) }) { Text("${r.hiddenByScope} more in history") }
        }
    }
}

@Composable
private fun NoResults(ui: SessionListUi, r: BrowserResult, onQuery: (String) -> Unit, onScope: (BrowserScope) -> Unit, onClearFilters: () -> Unit) {
    val q = ui.browserQuery
    val text = when {
        q.searching -> "Nothing matches “${ui.query.trim()}”" + if (ui.scope == BrowserScope.OPEN) " in open chats." else "."
        ui.scope == BrowserScope.OPEN -> "No open chats right now. Nothing is running, waiting for input or accepting replies."
        else -> "No chats match these filters."
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = FabClearance)) {
        item {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (ui.scope == BrowserScope.OPEN && r.hiddenByScope > 0) {
                        OutlinedButton(onClick = { onScope(BrowserScope.ALL) }) { Text("Show ${r.hiddenByScope} in history") }
                    }
                    if (r.hiddenByFilters > 0 || q.filtersSet) {
                        OutlinedButton(onClick = onClearFilters) { Text(if (r.hiddenByFilters > 0) "Clear filters (${r.hiddenByFilters} hidden)" else "Clear filters") }
                    }
                    if (q.searching) {
                        OutlinedButton(onClick = { onQuery("") }) { Text("Clear search") }
                    }
                }
            }
        }
    }
}

@Composable
private fun FolderHeader(row: FolderRow, searching: Boolean, onToggle: () -> Unit) {
    val open = !row.collapsed
    val counts = countLabel(row.chats, row.childAgents)
    Row(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .then(
                if (searching) {
                    Modifier
                } else {
                    Modifier.clickable(role = Role.Button, onClickLabel = if (open) "Collapse folder" else "Expand folder", onClick = onToggle)
                },
            )
            .semantics(mergeDescendants = true) {
                heading()
                contentDescription = "Folder ${row.label}, ${shortPath(row.folder)}, $counts"
                stateDescription = when {
                    row.revealedBySearch -> "Showing matches"
                    open -> "Expanded"
                    else -> "Collapsed"
                }
            }
            .heightIn(min = 48.dp)
            .padding(start = 12.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (open) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(
                row.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                counts, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Thin bar at the start of a row so selection is visible beside the open detail pane, not only by tint. */
@Composable
private fun SelectionBar(selected: Boolean) {
    Box(Modifier.width(4.dp).fillMaxHeight().background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent))
}

/** Indented row for a real child agent. Its status is its own, never the parent's. */
@Composable
fun ChildRow(s: Session, depth: Int, selected: Boolean, now: Instant, onClick: () -> Unit, context: Boolean = false) {
    val bg = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
    val rule = MaterialTheme.colorScheme.outlineVariant
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Surface(color = bg, contentColor = MaterialTheme.colorScheme.onSurface) {
        Row(
            Modifier.fillMaxWidth().height(IntrinsicSize.Min)
                .clickable(role = Role.Button, onClickLabel = "Inspect agent", onClick = onClick)
                .semantics {
                    this.selected = selected
                    stateDescription = "Child agent, " + statusLabel(s.status) + if (context) ", shown as context" else ""
                },
        ) {
            SelectionBar(selected)
            Spacer(Modifier.width(12.dp + 16.dp * depth.coerceAtMost(3)))
            Box(Modifier.width(2.dp).fillMaxHeight().background(rule))
            Column(Modifier.weight(1f).padding(start = 12.dp, end = 16.dp, top = 8.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        s.agentName?.takeIf { it.isNotBlank() } ?: s.title.ifBlank { "Agent" },
                        style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
                        color = if (context) muted else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(relativeTime(s.updatedAt, now), style = MaterialTheme.typography.labelSmall, color = muted)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusChip(s.status)
                    val kind = listOfNotNull("Child agent", s.agentRole?.takeIf { it.isNotBlank() }).joinToString(" · ")
                    Text(kind, style = MaterialTheme.typography.labelMedium, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                preview(s.stage, s.lastMessage ?: s.task)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                ProgressLine(s.progress, s.status)
            }
        }
    }
}

private fun preview(stage: String?, message: String?): String? =
    listOfNotNull(stage?.takeIf { it.isNotBlank() }, message?.takeIf { it.isNotBlank() }?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim())
        .joinToString(" · ").ifBlank { null }

@Composable
private fun EmptyState(text: String) {
    // Scrollable so pull-to-refresh still works on an empty list.
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = FabClearance)) {
        item {
            Text(
                text,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(24.dp),
            )
        }
    }
}

@Composable
fun ConnectionLine(connection: Connection, onRePair: () -> Unit) {
    val colors = LocalStatusColors.current
    val (dot, text) = when (connection) {
        Connection.Live -> colors.done to "Live"
        Connection.Connecting -> colors.neutral to "Connecting…"
        Connection.Paused -> colors.neutral to "Paused in background"
        Connection.NotPaired -> colors.neutral to "Not paired"
        is Connection.Polling -> colors.attention to "Refreshing every ${connection.intervalSeconds} s · ${connection.reason}"
        is Connection.Unauthorized -> colors.error to connection.message
    }
    if (connection is Connection.Unauthorized) {
        Banner("The Mac no longer accepts this phone. ${connection.message}", Modifier.padding(horizontal = 16.dp, vertical = 4.dp), actionLabel = "Pair again", onAction = onRePair)
        return
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp).semantics(mergeDescendants = true) { contentDescription = "Connection: $text" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(Modifier.size(8.dp), shape = CircleShape, color = dot) {}
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun SessionRow(
    s: Session,
    selected: Boolean,
    now: Instant,
    onClick: () -> Unit,
    childCount: Int = 0,
    activeChildren: Int = 0,
    expanded: Boolean = false,
    onToggle: () -> Unit = {},
    context: Boolean = false,
    revealedBySearch: Boolean = false,
    scope: BrowserScope = BrowserScope.ALL,
) {
    val bg = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Surface(color = bg, contentColor = MaterialTheme.colorScheme.onSurface) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .clickable(role = Role.Button, onClickLabel = "Open chat", onClick = onClick)
                .semantics {
                    this.selected = selected
                    stateDescription = statusLabel(s.status) + (if (s.unread > 0) ", ${s.unread} unread" else "") +
                        if (context) ", parent of a matching agent" else ""
                },
        ) {
            SelectionBar(selected)
            Column(
                Modifier.weight(1f).padding(start = 12.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        s.title.ifBlank { s.agentName?.takeIf { it.isNotBlank() } ?: shortPath(s.cwd).ifBlank { s.id } },
                        style = MaterialTheme.typography.titleMedium,
                        color = if (context) muted else MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(relativeTime(s.updatedAt, now), style = MaterialTheme.typography.labelSmall, color = muted, modifier = Modifier.padding(top = 3.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusChip(s.status)
                    Text(
                        rowKind(s, context), style = MaterialTheme.typography.labelMedium, color = muted,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                    if (s.unread > 0) {
                        Surface(color = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(10.dp)) {
                            Text("${s.unread}", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp))
                        }
                    }
                }
                preview(s.stage, s.lastMessage)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                ProgressLine(s.progress, s.status)
            }
        }
    }
    if (childCount > 0) {
        val noun = if (scope == BrowserScope.OPEN && !revealedBySearch) "open child agent" else "child agent"
        val label = when {
            revealedBySearch -> plural(childCount, "matching child agent", "matching child agents")
            else -> plural(childCount, noun, noun + "s")
        } + if (activeChildren > 0) " · $activeChildren active" else ""
        Row(
            Modifier.fillMaxWidth()
                .then(
                    if (revealedBySearch) {
                        Modifier.semantics { stateDescription = "Shown by search" }
                    } else {
                        Modifier
                            .clickable(role = Role.Button, onClickLabel = if (expanded) "Hide child agents" else "Show child agents", onClick = onToggle)
                            .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }
                    },
                )
                .heightIn(min = 48.dp)
                .padding(start = 12.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (expanded) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null, tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(4.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
    }
    HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

/** "Claude Code · Chat", plus a subfolder when the chat runs below its project root, plus "view only" when replies are impossible. */
private fun rowKind(s: Session, context: Boolean = false): String {
    val kind = when {
        s.isSubagent -> "Agent"
        context -> "Parent chat"
        else -> "Chat"
    }
    val root = s.projectKey
    val cwd = s.cwd.trimEnd('/')
    val sub = if (cwd.length > root.length && cwd.startsWith("$root/")) cwd.removePrefix("$root/") else null
    return listOfNotNull(
        Agents.displayName(s.agent), kind, sub,
        if (!s.capabilities.send && !s.isSubagent) "view only" else null,
    ).joinToString(" · ")
}
