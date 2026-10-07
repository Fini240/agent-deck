package de.finn.agentdeck.ui.newsession

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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import de.finn.agentdeck.core.model.AgentModels
import de.finn.agentdeck.core.model.Agents
import de.finn.agentdeck.ui.components.Banner
import de.finn.agentdeck.ui.components.SectionTitle
import de.finn.agentdeck.ui.components.relativeTime
import de.finn.agentdeck.ui.components.shortPath

sealed interface ModelChoice {
    data object AgentDefault : ModelChoice
    data class Listed(val id: String) : ModelChoice
    data object Custom : ModelChoice
}

data class NewSessionUi(
    val agents: List<AgentModels> = emptyList(),
    val modelsLoading: Boolean = false,
    val modelsError: String? = null,
    val modelsRefreshedAt: String? = null,
    val agent: String = Agents.CLAUDE,
    val choice: ModelChoice = ModelChoice.AgentDefault,
    val customModel: String = "",
    val defaultModel: String? = null,
    val workspaces: List<String> = emptyList(),
    val cwd: String = "",
    val prompt: String = "",
    val starting: Boolean = false,
    val error: String? = null,
) {
    val currentAgent: AgentModels? get() = agents.firstOrNull { it.id == agent }

    /** Exact string sent as `model`, or null for the agent's own default. */
    val modelToSend: String? get() = when (val c = choice) {
        ModelChoice.AgentDefault -> null
        is ModelChoice.Listed -> c.id
        ModelChoice.Custom -> customModel.trim().ifEmpty { null }
    }

    val canStart: Boolean get() = !starting && cwd.isNotBlank() && (choice != ModelChoice.Custom || customModel.isNotBlank())
}

data class NewSessionActions(
    val onBack: () -> Unit = {},
    val onAgent: (String) -> Unit = {},
    val onChoice: (ModelChoice) -> Unit = {},
    val onCustom: (String) -> Unit = {},
    val onCwd: (String) -> Unit = {},
    val onPrompt: (String) -> Unit = {},
    val onRefreshModels: () -> Unit = {},
    val onStart: () -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewSessionScreen(ui: NewSessionUi, actions: NewSessionActions, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().imePadding()) {
        TopAppBar(
            navigationIcon = { IconButton(onClick = actions.onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            title = { Text("New session") },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        )
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
                SectionTitle("Agent")
                val ids = ui.agents.map { it.id }.ifEmpty { listOf(Agents.CLAUDE, Agents.CODEX) }
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    ids.forEachIndexed { i, id ->
                        val a = ui.agents.firstOrNull { it.id == id }
                        SegmentedButton(
                            selected = ui.agent == id, onClick = { actions.onAgent(id) },
                            shape = SegmentedButtonDefaults.itemShape(i, ids.size),
                        ) { Text(a?.name?.takeIf { it.isNotBlank() && it != id } ?: Agents.displayName(id)) }
                    }
                }
                ui.currentAgent?.let { a ->
                    if (!a.available) Banner("${Agents.displayName(a.id)} is not available on this device${a.error?.let { ": $it" } ?: "."}", Modifier.padding(top = 8.dp))
                    else a.version?.let { Text("Installed: $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp)) }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionTitle("Model", Modifier.weight(1f))
                    TextButton(onClick = actions.onRefreshModels, enabled = !ui.modelsLoading) { Text(if (ui.modelsLoading) "Refreshing…" else "Refresh list") }
                }
                val a = ui.currentAgent
                Text(
                    buildString {
                        append(if (a?.modelSource != null) "Source: ${a.modelSource}" else "Models are discovered from the installed CLI on this device.")
                        (a?.modelRefreshedAt ?: ui.modelsRefreshedAt)?.let { append(" · updated ${relativeTime(it)}") }
                    },
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ui.modelsError?.let { Banner(it, Modifier.padding(vertical = 6.dp), actionLabel = "Retry", onAction = actions.onRefreshModels) }
                a?.error?.takeIf { a.available }?.let { Banner("Model discovery: $it", Modifier.padding(vertical = 6.dp), isError = false) }
                Column(Modifier.selectableGroup().padding(top = 4.dp)) {
                    ModelRow(
                        "Agent default", ui.defaultModel?.let { "Device default: $it" } ?: "Whatever ${Agents.displayName(ui.agent)} uses by default",
                        ui.choice == ModelChoice.AgentDefault,
                    ) { actions.onChoice(ModelChoice.AgentDefault) }
                    a?.models.orEmpty().forEach { m ->
                        val sub = listOfNotNull(m.id.takeIf { it != m.label }, m.description, m.source?.let { "($it)" }).joinToString(" · ").ifBlank { null }
                        ModelRow(m.label, sub, ui.choice == ModelChoice.Listed(m.id)) { actions.onChoice(ModelChoice.Listed(m.id)) }
                    }
                    ModelRow("Exact model ID…", "Type any ID the CLI accepts, e.g. a model released after this app", ui.choice == ModelChoice.Custom) { actions.onChoice(ModelChoice.Custom) }
                }
                if (ui.choice == ModelChoice.Custom) {
                    OutlinedTextField(
                        value = ui.customModel, onValueChange = actions.onCustom, singleLine = true,
                        label = { Text("Model ID (sent exactly as typed)") },
                        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                }

                SectionTitle("Folder on this device")
                OutlinedTextField(
                    value = ui.cwd, onValueChange = actions.onCwd, singleLine = true,
                    label = { Text("Working directory") }, modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text("Must be inside an allowed workspace.") },
                )
                if (ui.workspaces.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ui.workspaces.forEach { w -> FilterChip(selected = ui.cwd == w, onClick = { actions.onCwd(w) }, label = { Text(shortPath(w)) }) }
                    }
                }

                SectionTitle("First message (optional)")
                OutlinedTextField(
                    value = ui.prompt, onValueChange = actions.onPrompt, minLines = 3, maxLines = 8,
                    modifier = Modifier.fillMaxWidth(), placeholder = { Text("What should the agent do?") },
                )
                ui.error?.let { Banner(it, Modifier.padding(top = 12.dp)) }
                Button(onClick = actions.onStart, enabled = ui.canStart, modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp).heightIn(min = 48.dp)) {
                    Text(if (ui.starting) "Starting…" else "Start ${Agents.displayName(ui.agent)}")
                }
            }
        }
    }
}

@Composable
private fun ModelRow(title: String, subtitle: String?, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = selected, onClick = onClick, role = Role.RadioButton).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.padding(start = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}
