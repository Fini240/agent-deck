package de.finn.agentdeck.ui.sessions

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Stroke-only star makes the unpinned state distinct without depending on colour. */
internal val UnpinnedChatIcon: ImageVector = ImageVector.Builder("UnpinnedChat", 24.dp, 24.dp, 24f, 24f).apply {
    path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.7f) {
        moveTo(12f, 3f)
        lineTo(14.8f, 8.8f)
        lineTo(21.2f, 9.7f)
        lineTo(16.6f, 14.2f)
        lineTo(17.7f, 20.6f)
        lineTo(12f, 17.6f)
        lineTo(6.3f, 20.6f)
        lineTo(7.4f, 14.2f)
        lineTo(2.8f, 9.7f)
        lineTo(9.2f, 8.8f)
        close()
    }
}.build()
