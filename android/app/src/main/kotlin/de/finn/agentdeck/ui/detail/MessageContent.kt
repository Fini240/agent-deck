package de.finn.agentdeck.ui.detail

import android.content.ClipData
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import de.finn.agentdeck.core.model.ChatBlock
import de.finn.agentdeck.core.model.ChatText
import de.finn.agentdeck.core.model.InlineSpan
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Copies [text] to the system clipboard. Tests swap in a recorder via [MessageContent]'s `onCopy`. */
@Composable
fun rememberClipboardCopier(): (String) -> Unit {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    return remember(clipboard, scope) { { text -> scope.launch { clipboard.setClipEntry(ClipData.newPlainText("Code", text).toClipEntry()) } } }
}

/**
 * Assistant/system text with a light Markdown subset ([ChatText]). Lives inside the chat's
 * SelectionContainer, so prose stays selectable; only the code copy buttons opt out of selection.
 */
@Composable
fun MessageContent(text: String, modifier: Modifier = Modifier, onCopy: (String) -> Unit = rememberClipboardCopier()) {
    val blocks = remember(text) { ChatText.parse(text) }
    val body = MaterialTheme.typography.bodyMedium
    val codeBg = MaterialTheme.colorScheme.surfaceContainerHighest
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (blocks.isEmpty()) Text("(empty)", style = body)
        blocks.forEach { b ->
            when (b) {
                is ChatBlock.Paragraph -> Text(annotated(b.spans, codeBg), style = body)
                is ChatBlock.Heading -> Text(
                    annotated(b.spans, codeBg),
                    style = if (b.level <= 2) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 2.dp).semantics { heading() },
                )
                is ChatBlock.Bullet -> Row(Modifier.padding(start = (b.indent * 16).dp)) {
                    Text(b.marker, style = body, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.widthIn(min = 16.dp).padding(end = 6.dp))
                    Text(annotated(b.spans, codeBg), style = body, modifier = Modifier.weight(1f))
                }
                is ChatBlock.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
                    Box(Modifier.width(3.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(2.dp)))
                    Spacer(Modifier.width(8.dp))
                    Text(annotated(b.spans, codeBg), style = body, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                }
                is ChatBlock.Code -> CodeBlock(b, codeBg, onCopy)
                ChatBlock.Rule -> HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

@Composable
private fun CodeBlock(b: ChatBlock.Code, bg: Color, onCopy: (String) -> Unit) {
    var copied by remember(b.code) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) { delay(2_000); copied = false }
    }
    val label = listOfNotNull(b.language, if (!b.closed) "still writing…" else null).joinToString(" · ").ifEmpty { "code" }
    Surface(color = bg, shape = RoundedCornerShape(6.dp), modifier = Modifier.fillMaxWidth()) {
        Column {
            DisableSelection {
                Row(Modifier.fillMaxWidth().padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    TextButton(
                        onClick = { onCopy(b.code); copied = true },
                        modifier = Modifier.semantics { contentDescription = if (copied) "Code copied" else "Copy ${b.language?.let { "$it " } ?: ""}code" },
                    ) { Text(if (copied) "Copied" else "Copy code") }
                }
            }
            Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 10.dp, end = 10.dp, bottom = 10.dp)) {
                Text(
                    b.code.ifEmpty { " " },
                    style = MaterialTheme.typography.bodySmall.merge(TextStyle(fontFamily = FontFamily.Monospace)),
                    softWrap = false,
                )
            }
        }
    }
}

private fun annotated(spans: List<InlineSpan>, codeBg: Color): AnnotatedString = buildAnnotatedString {
    spans.forEach { s ->
        val style = SpanStyle(
            fontWeight = if (s.strong) FontWeight.SemiBold else null,
            fontFamily = if (s.code) FontFamily.Monospace else null,
            background = if (s.code) codeBg else Color.Unspecified,
        )
        withStyle(style) { append(s.text) }
    }
}
