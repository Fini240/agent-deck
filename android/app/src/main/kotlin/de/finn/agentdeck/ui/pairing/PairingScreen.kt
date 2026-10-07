package de.finn.agentdeck.ui.pairing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import de.finn.agentdeck.ui.components.Banner
import de.finn.agentdeck.ui.components.SectionTitle

data class PairingUi(
    val server: String = "",
    val code: String = "",
    val deviceName: String = "",
    val busy: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val canGoBack: Boolean = false,
)

data class PairingActions(
    val onServer: (String) -> Unit = {},
    val onCode: (String) -> Unit = {},
    val onDeviceName: (String) -> Unit = {},
    val onScan: () -> Unit = {},
    val onPair: () -> Unit = {},
    val onBack: () -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairingScreen(ui: PairingUi, actions: PairingActions, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().imePadding()) {
        TopAppBar(
            navigationIcon = { if (ui.canGoBack) IconButton(onClick = actions.onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            title = { Text("Pair with your device") },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        )
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 560.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "On your computer or server, run ad pair. It shows a QR code with a one-time code that expires after a few minutes. " +
                        "This phone needs Tailscale connected.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ui.notice?.let { Banner(it, isError = false) }
                Button(onClick = actions.onScan, enabled = !ui.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Scan QR code") }
                HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
                SectionTitle("Or enter it manually")
                OutlinedTextField(
                    value = ui.server, onValueChange = actions.onServer, singleLine = true,
                    label = { Text("Server address (https)") },
                    placeholder = { Text("https://your-device.tailnet.ts.net:10443") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = ui.code, onValueChange = actions.onCode, singleLine = true,
                    label = { Text("One-time pairing code") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = ui.deviceName, onValueChange = actions.onDeviceName, singleLine = true,
                    label = { Text("Name for this phone") }, modifier = Modifier.fillMaxWidth(),
                )
                ui.error?.let { Banner(it) }
                OutlinedButton(
                    onClick = actions.onPair, enabled = !ui.busy && ui.server.isNotBlank() && ui.code.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text(if (ui.busy) "Pairing…" else "Pair") }
                Text(
                    "Only HTTPS addresses with a trusted certificate are accepted. Codes are single-use, so a failed attempt may need a new code.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
