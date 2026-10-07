package de.finn.agentdeck.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import de.finn.agentdeck.update.UpdatePhase
import de.finn.agentdeck.update.UpdateUi

/** In-app pop-up: visible even when Android notification permission is turned off. */
@Composable
fun UpdatePrompt(ui: UpdateUi, onUpdateNow: () -> Unit, onLater: () -> Unit) {
    AlertDialog(
        onDismissRequest = onLater,
        title = { Text("Agent Deck update available") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(if (ui.phase == UpdatePhase.READY) "Version ${ui.offer?.version} is downloaded and ready to install."
                    else "Version ${ui.offer?.version} is available. Download and install it now, or keep using the app and update later.")
            }
        },
        confirmButton = { TextButton(onClick = onUpdateNow) { Text("Update now") } },
        dismissButton = { TextButton(onClick = onLater) { Text("Later") } },
    )
}
