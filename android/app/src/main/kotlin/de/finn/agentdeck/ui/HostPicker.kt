package de.finn.agentdeck.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.finn.agentdeck.data.SavedHost

/** Always accessible, including from an offline device or open chat. */
@Composable
fun HostPicker(hosts: List<SavedHost>, active: String?, onSelect: (String) -> Unit, onAdd: () -> Unit, onRename: (String, String) -> Unit, onRemove: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<SavedHost?>(null) }
    var removing by remember { mutableStateOf<SavedHost?>(null) }
    var label by remember { mutableStateOf("") }
    val selected = hosts.firstOrNull { it.key == active }
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton(onClick = { expanded = true }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
            Text("${selected?.label ?: "Devices"} ▾", modifier = Modifier.fillMaxWidth())
        }
        TextButton(onClick = onAdd, modifier = Modifier.heightIn(min = 48.dp)) { Text("Add device") }
    }
    if (expanded) AlertDialog(
        onDismissRequest = { expanded = false }, title = { Text("Your devices") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                hosts.forEach { host ->
                    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                        TextButton(onClick = { if (host.usable) { onSelect(host.key); expanded = false } }, enabled = host.usable, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(if (host.key == active) "${host.label} · Selected" else host.label)
                                Text(host.error ?: host.server.host, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        Row {
                            TextButton(onClick = { label = host.label; editing = host; expanded = false }) { Text("Rename") }
                            TextButton(onClick = { removing = host; expanded = false }) { Text("Remove") }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { expanded = false; onAdd() }) { Text("Add device") } },
        dismissButton = { TextButton(onClick = { expanded = false }) { Text("Close") } },
    )
    removing?.let { host ->
        AlertDialog(onDismissRequest = { removing = null }, title = { Text("Remove ${host.label}?") },
            text = { Text("Remove its saved pairing from this phone. Running tasks will keep working on the device. You can add it again with a fresh pairing code.") },
            confirmButton = { TextButton(onClick = { onRemove(host.key); removing = null }) { Text("Remove device") } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } })
    }
    editing?.let { host ->
        AlertDialog(onDismissRequest = { editing = null }, title = { Text("Device name") },
            text = { OutlinedTextField(value = label, onValueChange = { label = it.take(40) }, label = { Text("Name") }, singleLine = true) },
            confirmButton = { TextButton(enabled = label.isNotBlank(), onClick = { onRename(host.key, label); editing = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } })
    }
}
