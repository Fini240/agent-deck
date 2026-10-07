package de.finn.agentdeck.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.finn.agentdeck.core.model.Progress
import de.finn.agentdeck.core.model.SessionStatus
import de.finn.agentdeck.ui.theme.LocalStatusColors
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeParseException

fun statusLabel(s: SessionStatus) = when (s) {
    SessionStatus.WORKING -> "Working"
    SessionStatus.IDLE -> "Idle"
    SessionStatus.NEEDS_INPUT -> "Needs input"
    SessionStatus.COMPLETED -> "Completed"
    SessionStatus.ERROR -> "Error"
    SessionStatus.UNKNOWN -> "Unknown"
    SessionStatus.OFFLINE -> "Offline"
}

@Composable
fun StatusChip(status: SessionStatus, modifier: Modifier = Modifier) {
    val color = LocalStatusColors.current.forStatus(status)
    Row(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(Modifier.size(8.dp), shape = CircleShape, color = color) {}
        Spacer(Modifier.width(6.dp))
        Text(statusLabel(status), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

/**
 * Real ratio when the Mac reported one; an indeterminate bar (motion that shows state) when work
 * is happening without a known total; nothing otherwise.
 */
@Composable
fun ProgressLine(progress: Progress?, status: SessionStatus, modifier: Modifier = Modifier) {
    val fraction = progress?.fraction
    when {
        fraction != null -> Column(modifier) {
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = progressText(progress) },
            )
            Text(progressText(progress), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
        }
        status == SessionStatus.WORKING -> LinearProgressIndicator(
            modifier.fillMaxWidth().semantics { contentDescription = "Working, total unknown" },
        )
        else -> Unit
    }
}

fun progressText(p: Progress): String {
    fun n(v: Double) = if (v == Math.floor(v)) v.toLong().toString() else String.format(java.util.Locale.ROOT, "%.1f", v)
    val pct = p.fraction?.let { " · ${(it * 100).toInt()}%" } ?: ""
    return "${n(p.current)} of ${n(p.total)}${p.unit?.let { " $it" } ?: ""}$pct"
}

@Composable
fun Banner(
    text: String,
    modifier: Modifier = Modifier,
    isError: Boolean = true,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val bg = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer
    val fg = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer
    Surface(color = bg, contentColor = fg, shape = RoundedCornerShape(8.dp), modifier = modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }) {
        Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(vertical = 6.dp))
            if (actionLabel != null && onAction != null) {
                TextButton(onClick = onAction) { Text(actionLabel, color = fg, fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(top = 20.dp, bottom = 8.dp),
    )
}

@Composable
fun KeyValue(key: String, value: String, modifier: Modifier = Modifier, valueColor: Color = Color.Unspecified) {
    Row(modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(key, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(120.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = valueColor, modifier = Modifier.weight(1f))
    }
}

/** "just now", "5 min ago", "3 h ago", "2 d ago"; null-safe for missing timestamps. */
fun relativeTime(iso: String?, now: Instant = Instant.now()): String {
    val t = try {
        iso?.let { Instant.parse(it) }
    } catch (_: DateTimeParseException) {
        null
    } ?: return ""
    val d = Duration.between(t, now)
    return when {
        d.isNegative || d.seconds < 45 -> "just now"
        d.toMinutes() < 60 -> "${d.toMinutes()} min ago"
        d.toHours() < 24 -> "${d.toHours()} h ago"
        else -> "${d.toDays()} d ago"
    }
}

fun shortPath(path: String): String {
    val home = Regex("^/Users/[^/]+")
    val p = home.replace(path, "~")
    return if (p.length <= 42) p else "…" + p.takeLast(40)
}
