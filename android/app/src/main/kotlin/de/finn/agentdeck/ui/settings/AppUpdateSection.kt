package de.finn.agentdeck.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.finn.agentdeck.ui.components.SectionTitle
import de.finn.agentdeck.update.UpdatePhase
import de.finn.agentdeck.update.UpdateUi

@Composable
fun AppUpdateSection(ui: UpdateUi, installedVersion: String, actions: SettingsActions, paired: Boolean = true) {
    Column(Modifier.fillMaxWidth()) {
        SectionTitle("App updates")
        Text("Installed: $installedVersion", style = MaterialTheme.typography.bodySmall)
        val description = when (ui.phase) {
            UpdatePhase.IDLE -> if (paired) "Check your Mac for a newer version." else "Pair with your Mac to check for updates."
            UpdatePhase.CHECKING -> "Checking your Mac…"
            UpdatePhase.CURRENT -> "You're up to date with the APK on your Mac."
            UpdatePhase.UNAVAILABLE -> "No update APK is available on your Mac yet."
            UpdatePhase.AVAILABLE -> "Version ${ui.offer?.version} is available."
            UpdatePhase.DOWNLOADING -> "Downloading ${ui.offer?.version}: ${ui.downloaded * 100 / (ui.offer?.size ?: 1)}%"
            UpdatePhase.READY -> "Version ${ui.offer?.version} is ready to install."
            UpdatePhase.ERROR -> "Update unsuccessful"
        }
        Text(description, modifier = Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium)
        ui.message?.let { Text(it, modifier = Modifier.padding(top = 8.dp), color = if (ui.phase == UpdatePhase.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
        if (ui.phase == UpdatePhase.DOWNLOADING) {
            LinearProgressIndicator(progress = { (ui.downloaded.toFloat() / (ui.offer?.size ?: 1)).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
        } else if (ui.phase == UpdatePhase.CHECKING) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
        }
        if (ui.phase == UpdatePhase.READY) {
            Button(onClick = actions.onInstallUpdate, modifier = Modifier.padding(top = 8.dp)) { Text("Install update") }
            Text("Android will ask you to confirm. If prompted, allow Agent Deck to install apps.", style = MaterialTheme.typography.bodySmall)
        } else if (ui.offer != null && !ui.busy) {
            Button(onClick = actions.onDownloadUpdate, modifier = Modifier.padding(top = 8.dp), enabled = paired) { Text(if (ui.phase == UpdatePhase.ERROR) "Retry download" else "Download update") }
        }
        OutlinedButton(onClick = actions.onCheckUpdate, enabled = paired && !ui.busy, modifier = Modifier.padding(top = 8.dp, bottom = 8.dp)) {
                Text(if (ui.phase == UpdatePhase.ERROR) "Check again" else "Check for updates")
        }
    }
}
