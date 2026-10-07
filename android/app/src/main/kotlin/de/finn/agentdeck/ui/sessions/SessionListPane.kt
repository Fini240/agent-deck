package de.finn.agentdeck.ui.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.VerticalDivider
import androidx.compose.ui.semantics.heading
import de.finn.agentdeck.core.model.ActivityFilter
import de.finn.agentdeck.core.model.ProjectGroup
import de.finn.agentdeck.core.model.SessionGrouping
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.finn.agentdeck.core.model.Agents
import de.finn.agentdeck.core.model.Session
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
) {
    val groups: List<ProjectGroup> get() = SessionGrouping.group(sessions, activity, filter)
}

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
) {
    Column(modifier.fillMaxSize()) {
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
                IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, contentDescription = "Refresh sessions") }
                IconButton(onClick = onNew) { Icon(Icons.Default.Add, contentDescription = "New session") }
                IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, contentDescription = "Settings and connection") }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        )
        ConnectionLine(ui.connection, onRePair)
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(selected = ui.activity == ActivityFilter.ALL, onClick = { onActivity(ActivityFilter.ALL) }, label = { Text("All") })
            FilterChip(selected = ui.activity == ActivityFilter.ACTIVE, onClick = { onActivity(ActivityFilter.ACTIVE) }, label = { Text("Active") })
            VerticalDivider(Modifier.height(24.dp))
            listOf(Agents.CLAUDE to "Claude", Agents.CODEX to "Codex").forEach { (id, label) ->
                FilterChip(selected = ui.filter == id, onClick = { onFilter(if (ui.filter == id) "all" else id) }, label = { Text(label) })
            }
        }
        if (ui.error != null && ui.connection !is Connection.Unauthorized) {
            Banner(ui.error, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), actionLabel = "Retry", onAction = onRefresh)
        }
        PullToRefreshBox(isRefreshing = ui.loading && ui.loaded, onRefresh = onRefresh, modifier = Modifier.weight(1f)) {
            when {
                !ui.loaded && ui.error == null -> EmptyState("Loading sessions…")
                ui.groups.isEmpty() && ui.loaded -> EmptyState(
                    when {
                        ui.activity == ActivityFilter.ACTIVE -> "Nothing is working or waiting for input right now."
                        ui.filter != "all" -> "No ${Agents.displayName(ui.filter)} sessions."
                        else -> "No sessions on this Mac yet. Start Claude Code or Codex in a terminal, or tap + to start one here."
                    },
                )
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                    ui.groups.forEach { group ->
                        item(key = "g:" + group.key) { ProjectHeader(group) }
                        group.entries.forEach { entry ->
                            val open = entry.session.id in ui.expanded
                            item(key = entry.session.id) {
                                SessionRow(
                                    entry.session, selected = entry.session.id == ui.selectedId, now = ui.now,
                                    onClick = { onSelect(entry.session.id) },
                                    childCount = entry.totalChildren, activeChildren = entry.activeChildren,
                                    expanded = open, onToggle = { onToggleExpand(entry.session.id) },
                                )
                            }
                            if (open) {
                                items(entry.children, key = { "c:" + it.session.id }) { child ->
                                    ChildRow(child.session, child.depth, selected = child.session.id == ui.selectedId, now = ui.now, onClick = { onSelect(child.session.id) })
                                }
                                if (entry.children.isEmpty() && entry.totalChildren > 0) {
                                    item(key = "none:" + entry.session.id) {
                                        Text(
                                            "No active child agents. Switch to All to see finished ones.",
                                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(start = 32.dp, end = 16.dp, top = 4.dp, bottom = 8.dp),
                                        )
                                    }
                                }
                            }
                            item(key = "d:" + entry.session.id) { HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProjectHeader(group: ProjectGroup) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).padding(horizontal = 16.dp, vertical = 8.dp)
            .semantics(mergeDescendants = true) { heading() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(group.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(shortPath(group.key), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(
            "${group.sessionCount} agent${if (group.sessionCount == 1) "" else "s"} · ${group.activeCount} active",
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Indented row for a real child agent. Its status is its own, never the parent's. */
@Composable
fun ChildRow(s: Session, depth: Int, selected: Boolean, now: Instant, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
    val rule = MaterialTheme.colorScheme.outlineVariant
    Surface(color = bg, contentColor = MaterialTheme.colorScheme.onSurface) {
        Row(
            Modifier.fillMaxWidth().height(IntrinsicSize.Min)
                .clickable(role = Role.Button, onClickLabel = "Inspect agent", onClick = onClick)
                .semantics { stateDescription = "Child agent, " + statusLabel(s.status) }
                .padding(start = 16.dp + 16.dp * depth, end = 16.dp),
        ) {
            Box(Modifier.width(2.dp).fillMaxHeight().background(rule))
            Column(Modifier.weight(1f).padding(start = 12.dp, top = 10.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        s.agentName?.takeIf { it.isNotBlank() } ?: s.title.ifBlank { "Agent" },
                        style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                    Text(relativeTime(s.updatedAt, now), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusChip(s.status)
                    val role = listOfNotNull(s.agentRole?.takeIf { it.isNotBlank() }, s.stage?.takeIf { it.isNotBlank() }).joinToString(" · ")
                    if (role.isNotEmpty()) {
                        Text(role, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                (s.lastMessage ?: s.task)?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                ProgressLine(s.progress, s.status)
            }
        }
    }
}

@Composable
private fun EmptyState(text: String) {
    // Scrollable so pull-to-refresh still works on an empty list.
    LazyColumn(Modifier.fillMaxSize()) {
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
) {
    val bg = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
    Surface(color = bg, contentColor = MaterialTheme.colorScheme.onSurface) {
        Column(
            Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClickLabel = "Open session", onClick = onClick)
                .semantics { stateDescription = statusLabel(s.status) + if (s.unread > 0) ", ${s.unread} unread" else "" }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    Agents.displayName(s.agent).uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                )
                if (!s.managed) {
                    Text("  ·  view only", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.weight(1f))
                Text(relativeTime(s.updatedAt, now), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                s.title.ifBlank { shortPath(s.cwd).ifBlank { s.id } },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusChip(s.status)
                s.stage?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                }
                Spacer(Modifier.weight(1f))
                if (s.unread > 0) {
                    Surface(color = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(10.dp)) {
                        Text("${s.unread}", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp))
                    }
                }
            }
            s.lastMessage?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (s.cwd.isNotBlank()) {
                Text(shortPath(s.cwd), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box(Modifier.padding(top = 2.dp)) { ProgressLine(s.progress, s.status) }
        }
    }
    if (childCount > 0) {
        val label = "$childCount agent${if (childCount == 1) "" else "s"}" + if (activeChildren > 0) " · $activeChildren active" else ""
        Row(
            Modifier.fillMaxWidth()
                .clickable(role = Role.Button, onClickLabel = if (expanded) "Hide child agents" else "Show child agents", onClick = onToggle)
                .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }
                .padding(start = 8.dp, end = 16.dp)
                .heightIn(min = 48.dp),
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
}
