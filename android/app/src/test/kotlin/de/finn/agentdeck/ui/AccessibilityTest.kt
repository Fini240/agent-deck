package de.finn.agentdeck.ui

import android.app.Application
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.finn.agentdeck.core.model.ActivityFilter
import de.finn.agentdeck.data.Connection
import de.finn.agentdeck.ui.detail.DetailActions
import de.finn.agentdeck.ui.detail.DetailUi
import de.finn.agentdeck.ui.detail.SessionDetailPane
import de.finn.agentdeck.ui.sessions.SessionListPane
import de.finn.agentdeck.ui.sessions.SessionListUi
import de.finn.agentdeck.ui.theme.AgentDeckTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, qualifiers = "w390dp-h844dp")
class AccessibilityTest {
    @get:Rule val compose = createComposeRule()

    private fun allNodes(): List<SemanticsNode> {
        val out = mutableListOf<SemanticsNode>()
        fun walk(n: SemanticsNode) { out += n; n.children.forEach(::walk) }
        walk(compose.onRoot(useUnmergedTree = false).fetchSemanticsNode())
        return out
    }

    /** Every clickable has a label and a touch target of at least 48 dp. */
    private fun assertClickablesAccessible() {
        val density = compose.density
        val bad = allNodes().filter { it.config.getOrNull(SemanticsActions.OnClick) != null }.mapNotNull { n ->
            val label = n.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
                ?: n.config.getOrNull(SemanticsProperties.Text)?.joinToString()
                ?: n.config.getOrNull(SemanticsProperties.EditableText)?.text?.takeIf { it.isNotBlank() }
                ?: n.config.getOrNull(SemanticsProperties.Text)?.joinToString()
                ?: n.config.getOrNull(SemanticsActions.OnClick)?.label
            val w = with(density) { n.touchBoundsInRoot.width.toDp() }
            val h = with(density) { n.touchBoundsInRoot.height.toDp() }
            when {
                label.isNullOrBlank() -> "unlabelled clickable at ${n.boundsInRoot}"
                w < 47.5.dp || h < 47.5.dp -> "'$label' touch target ${w}x$h"
                else -> null
            }
        }
        assertTrue(bad.joinToString("\n"), bad.isEmpty())
    }

    @Test
    fun groupedListIsAccessibleAndExpandToggles() {
        var expanded = setOf<String>()
        var toggled: String? = null
        compose.setContent {
            AgentDeckTheme(darkTheme = false) {
                SessionListPane(
                    SessionListUi(sessions = Fixtures.sessions, connection = Connection.Live, loaded = true, expanded = expanded, now = Fixtures.now),
                    {}, {}, { toggled = it }, {}, {}, {}, {}, {},
                )
            }
        }
        assertClickablesAccessible()
        compose.onNodeWithText("3 agents · 1 active").performClick()
        assertEquals(Fixtures.claudeParent.id, toggled)
        // Collapsed: child agents are not listed.
        assertEquals(0, compose.onAllNodesWithText("Search model docs", substring = false).fetchSemanticsNodes().count { it.config.getOrNull(SemanticsProperties.Text)?.joinToString() == "Search model docs" && it.parent?.config?.getOrNull(SemanticsProperties.StateDescription)?.startsWith("Child") == true })
        expanded = setOf(Fixtures.claudeParent.id)
    }

    @Test
    fun childDetailIsReadOnlyWithExplanation() {
        compose.setContent {
            AgentDeckTheme(darkTheme = true) {
                SessionDetailPane(
                    DetailUi(session = Fixtures.workingChild, parent = Fixtures.claudeParent, messages = Fixtures.childMessages, messagesLoaded = true, now = Fixtures.now),
                    DetailActions(onBack = {}),
                )
            }
        }
        compose.onNodeWithText("Child agents are inspect-only", substring = true).assertIsDisplayed()
        assertTrue(compose.onAllNodesWithText("Send").fetchSemanticsNodes().isEmpty())
        compose.onNodeWithText("Tool · WebFetch").assertIsDisplayed()
        compose.onNodeWithText("4 of 10 pages · 40%").assertIsDisplayed()
        assertClickablesAccessible()
    }

    @Test
    fun workingParentOffersInterruptSend() {
        compose.setContent {
            AgentDeckTheme {
                SessionDetailPane(DetailUi(session = Fixtures.claudeParent, messages = Fixtures.parentMessages, messagesLoaded = true, draft = "next"), DetailActions())
            }
        }
        compose.onNodeWithText("Interrupt\n& send").assertIsDisplayed()
        compose.onNodeWithText("You").assertIsDisplayed()
        compose.onNodeWithText("Tool · Agent").assertIsDisplayed()
        assertClickablesAccessible()
    }

    @Test
    fun activeFilterHidesFinishedChildren() {
        compose.setContent {
            AgentDeckTheme {
                SessionListPane(
                    SessionListUi(sessions = Fixtures.sessions, connection = Connection.Live, loaded = true, activity = ActivityFilter.ACTIVE, expanded = setOf(Fixtures.claudeParent.id), now = Fixtures.now),
                    {}, {}, {}, {}, {}, {}, {}, {},
                )
            }
        }
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Search model docs"))
        compose.onNodeWithText("Search model docs").assertIsDisplayed()
        assertTrue(compose.onAllNodesWithText("Review backend auth").fetchSemanticsNodes().isEmpty())
    }
}
