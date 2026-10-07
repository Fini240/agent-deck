package de.finn.agentdeck.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import de.finn.agentdeck.core.model.Agents
import de.finn.agentdeck.core.model.DefaultModels
import de.finn.agentdeck.core.model.ServerSettings
import de.finn.agentdeck.core.model.ServerStatus
import de.finn.agentdeck.core.model.SettingsPatch
import de.finn.agentdeck.data.Connection
import de.finn.agentdeck.push.PushStatus
import de.finn.agentdeck.ui.components.Banner
import de.finn.agentdeck.ui.components.KeyValue
import de.finn.agentdeck.ui.components.SectionTitle
import de.finn.agentdeck.ui.components.relativeTime
import de.finn.agentdeck.ui.theme.LocalStatusColors

data class SettingsUi(
    val serverName: String = "",
    val serverUrl: String = "",
    val deviceId: String = "",
    val connection: Connection = Connection.Connecting,
    val serverStatus: ServerStatus? = null,
    val statusError: String? = null,
    val statusLoading: Boolean = false,
    val push: PushStatus = PushStatus(buildConfigured = false),
    val permissionNeeded: Boolean = false,
    val blockedChannels: List<String> = emptyList(),
    val testMessage: String? = null,
    val testing: Boolean = false,
    val serverSettings: ServerSettings? = null,
    val settingsSaving: Boolean = false,
    val settingsMessage: String? = null,
    val appVersion: String = "",
)

data class SettingsActions(
    val onBack: () -> Unit = {},
    val onRefreshStatus: () -> Unit = {},
    val onRequestPermission: () -> Unit = {},
    val onOpenNotificationSettings: () -> Unit = {},
    val onLocalTest: () -> Unit = {},
    val onPushTest: () -> Unit = {},
    val onRegisterPush: () -> Unit = {},
    val onSaveSettings: (SettingsPatch) -> Unit = {},
    val onRePair: () -> Unit = {},
    val onForget: () -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(ui: SettingsUi, actions: SettingsActions, modifier: Modifier = Modifier) {
    var confirmForget by remember { mutableStateOf(false) }
    Column(modifier.fillMaxSize().imePadding()) {
        TopAppBar(
            navigationIcon = { IconButton(onClick = actions.onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            title = { Text("Settings") },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        )
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp)) {
                ConnectionSection(ui, actions, onForget = { confirmForget = true })
                HorizontalDivider(Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
                NotificationSection(ui, actions)
                HorizontalDivider(Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
                ui.serverSettings?.let { MacPreferences(it, ui, actions) } ?: run {
                    SectionTitle("Mac preferences")
                    Text("Loaded from the Mac once connected.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider(Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
                SectionTitle("About")
                KeyValue("App version", ui.appVersion)
                Text(
                    "No analytics or ads. Chats and controls go only between this phone and your Mac over Tailscale HTTPS. " +
                        "Notifications travel through Google's push service end-to-end encrypted with a key only this phone and your Mac have.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 24.dp),
                )
            }
        }
    }
    if (confirmForget) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text("Remove this phone?") },
            text = { Text("The Mac revokes this phone's access and the saved credentials are deleted here. You'll need a new pairing code.") },
            confirmButton = { TextButton(onClick = { confirmForget = false; actions.onForget() }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { confirmForget = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ConnectionSection(ui: SettingsUi, actions: SettingsActions, onForget: () -> Unit) {
    SectionTitle("Connection")
    KeyValue("Mac", ui.serverName.ifBlank { "—" })
    KeyValue("Address", ui.serverUrl)
    KeyValue("This phone", ui.deviceId.take(12).ifBlank { "—" })
    val (connText, ok) = when (val c = ui.connection) {
        Connection.Live -> "Live updates connected" to true
        Connection.Connecting -> "Connecting…" to null
        Connection.Paused -> "Paused (app in background)" to null
        Connection.NotPaired -> "Not paired" to false
        is Connection.Polling -> "Live stream down, refreshing every ${c.intervalSeconds} s: ${c.reason}" to false
        is Connection.Unauthorized -> "Not authorized: ${c.message}" to false
    }
    val colors = LocalStatusColors.current
    KeyValue("Status", connText, valueColor = when (ok) { true -> colors.done; false -> colors.error; null -> MaterialTheme.colorScheme.onSurface })
    ui.serverStatus?.let { st ->
        KeyValue("Helper", st.version ?: "unknown version")
        KeyValue("Session discovery", if (st.providers.available) "Available" else "Unavailable${st.providers.error?.let { ": $it" } ?: ""}")
        KeyValue("Terminal control", if (st.terminal.available) "Available" else "Unavailable${st.terminal.error?.let { ": $it" } ?: ""}")
        st.keepAwake?.let { k -> KeyValue("Keep Mac awake", (if (k.held) "Holding (agent working)" else "Not holding") + (k.mode?.let { " · mode $it" } ?: "") + (k.error?.let { " · $it" } ?: "")) }
        st.monitor?.refreshedAt?.let { KeyValue("Last scan", relativeTime(it)) }
    }
    ui.statusError?.let { Banner(it, Modifier.padding(vertical = 4.dp)) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
        OutlinedButton(onClick = actions.onRefreshStatus, enabled = !ui.statusLoading) { Text(if (ui.statusLoading) "Checking…" else "Check connection") }
        OutlinedButton(onClick = actions.onRePair) { Text("Pair again") }
        TextButton(onClick = onForget) { Text("Remove this phone", color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun NotificationSection(ui: SettingsUi, actions: SettingsActions) {
    SectionTitle("Notifications")
    val (level, headline, detail) = ui.push.describe()
    val colors = LocalStatusColors.current
    Text(
        headline, style = MaterialTheme.typography.titleSmall,
        color = when (level) { PushStatus.Level.OK -> colors.done; PushStatus.Level.WARNING -> colors.attention; PushStatus.Level.OFF -> colors.error },
    )
    Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
    ui.serverStatus?.push?.let { p ->
        KeyValue("Mac sender", if (p.available) "Ready${p.projectId?.let { " ($it)" } ?: ""}" else "Not ready${p.reason?.let { ": $it" } ?: ""}")
        KeyValue("Phones with token", p.devicesWithToken.toString())
    }
    if (ui.permissionNeeded) {
        Banner("Android notifications are off for Agent Deck.", Modifier.padding(vertical = 6.dp), isError = false, actionLabel = "Allow", onAction = actions.onRequestPermission)
    }
    if (ui.blockedChannels.isNotEmpty()) {
        Banner("Turned off in Android: ${ui.blockedChannels.joinToString()}", Modifier.padding(vertical = 6.dp), isError = false)
    }
    Text(
        "Progress updates are silent. Finished, failed and needs-input alerts ring or vibrate. Change sounds per category in Android settings.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp),
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
        Button(onClick = actions.onPushTest, enabled = !ui.testing && ui.push.buildConfigured) { Text(if (ui.testing) "Sending…" else "Send test from Mac") }
        OutlinedButton(onClick = actions.onLocalTest) { Text("Local test only") }
        OutlinedButton(onClick = actions.onOpenNotificationSettings) { Text("Android settings") }
        if (ui.push.buildConfigured && !ui.push.connected) OutlinedButton(onClick = actions.onRegisterPush) { Text("Register again") }
    }
    Text(
        "\"Send test from Mac\" goes through the real encrypted push path and only proves delivery when the notification appears. " +
            "\"Local test\" is shown by this phone alone.",
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp),
    )
    ui.testMessage?.let { Banner(it, Modifier.padding(vertical = 6.dp), isError = false) }
}

@Composable
private fun MacPreferences(s: ServerSettings, ui: SettingsUi, actions: SettingsActions) {
    SectionTitle("Mac preferences")
    var notifyOn by rememberSaveable(s) { mutableStateOf(s.notifyOn.toSet()) }
    var keepAwake by rememberSaveable(s) { mutableStateOf(s.keepAwakeMode ?: "active") }
    var interval by rememberSaveable(s) { mutableStateOf(s.progressIntervalSeconds?.toString().orEmpty()) }
    var defaultAgent by rememberSaveable(s) { mutableStateOf(s.defaultAgent ?: Agents.CLAUDE) }
    var claudeModel by rememberSaveable(s) { mutableStateOf(s.defaultModels.claude.orEmpty()) }
    var codexModel by rememberSaveable(s) { mutableStateOf(s.defaultModels.codex.orEmpty()) }

    Text("Notify me about", style = MaterialTheme.typography.labelLarge)
    listOf("progress" to "Progress (silent)", "completed" to "Finished", "error" to "Errors", "input" to "Needs input / permission").forEach { (k, label) ->
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(k in notifyOn, role = Role.Checkbox) { on -> notifyOn = if (on) notifyOn + k else notifyOn - k },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = k in notifyOn, onCheckedChange = null)
            Text(label, Modifier.padding(start = 12.dp))
        }
    }
    OutlinedTextField(
        value = interval, onValueChange = { v -> interval = v.filter(Char::isDigit).take(4) }, singleLine = true,
        label = { Text("Minimum seconds between progress updates") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
    )
    Text("Keep the Mac awake", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
    val modes = listOf("active" to "While working", "plugged_in" to "Only on power", "off" to "Never")
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        modes.forEachIndexed { i, (k, label) ->
            SegmentedButton(selected = keepAwake == k, onClick = { keepAwake = k }, shape = SegmentedButtonDefaults.itemShape(i, modes.size)) { Text(label, maxLines = 1) }
        }
    }
    Text("Default agent for new sessions", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        listOf(Agents.CLAUDE, Agents.CODEX).forEachIndexed { i, a ->
            SegmentedButton(selected = defaultAgent == a, onClick = { defaultAgent = a }, shape = SegmentedButtonDefaults.itemShape(i, 2)) { Text(Agents.displayName(a)) }
        }
    }
    OutlinedTextField(claudeModel, { claudeModel = it.trim() }, singleLine = true, label = { Text("Default Claude Code model ID (blank = CLI default)") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
    OutlinedTextField(codexModel, { codexModel = it.trim() }, singleLine = true, label = { Text("Default Codex model ID (blank = CLI default)") }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
    if (s.allowedWorkspaces.isNotEmpty()) {
        Text("Allowed workspaces (change on the Mac)", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp))
        s.allowedWorkspaces.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    ui.settingsMessage?.let { Banner(it, Modifier.padding(top = 8.dp), isError = false) }
    Button(
        onClick = {
            actions.onSaveSettings(
                SettingsPatch(
                    defaultAgent = defaultAgent,
                    defaultModels = DefaultModels(claudeModel.ifBlank { null }, codexModel.ifBlank { null }),
                    notifyOn = listOf("progress", "completed", "error", "input").filter { it in notifyOn },
                    progressIntervalSeconds = interval.toIntOrNull(),
                    keepAwakeMode = keepAwake,
                ),
            )
        },
        enabled = !ui.settingsSaving, modifier = Modifier.padding(top = 12.dp),
    ) { Text(if (ui.settingsSaving) "Saving…" else "Save on Mac") }
}
