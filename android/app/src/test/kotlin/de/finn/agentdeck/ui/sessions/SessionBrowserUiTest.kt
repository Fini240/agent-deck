package de.finn.agentdeck.ui.sessions

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.finn.agentdeck.core.model.BrowserScope
import de.finn.agentdeck.data.Connection
import de.finn.agentdeck.ui.Fixtures
import de.finn.agentdeck.ui.theme.AgentDeckTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Drives the list the way MainViewModel does: state hoisted, callbacks update it. */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, qualifiers = "w390dp-h844dp")
class SessionBrowserUiTest {
    @get:Rule val compose = createComposeRule()

    private var ui by mutableStateOf(SessionListUi(sessions = Fixtures.sessions, connection = Connection.Live, loaded = true, now = Fixtures.now))

    private fun show() {
        compose.setContent {
            AgentDeckTheme {
                SessionListPane(
                    ui,
                    onFilter = { ui = ui.copy(filter = it) },
                    onActivity = { ui = ui.copy(activity = it) },
                    onToggleExpand = { id -> ui = ui.copy(expanded = if (id in ui.expanded) ui.expanded - id else ui.expanded + id) },
                    onSelect = { ui = ui.copy(selectedId = it) },
                    onRefresh = {}, onNew = {}, onSettings = {}, onRePair = {},
                    onScope = { ui = ui.copy(scope = it) },
                    onQuery = { ui = ui.copy(query = it) },
                    onToggleFolder = { k -> ui = ui.copy(collapsedFolders = if (k in ui.collapsedFolders) ui.collapsedFolders - k else ui.collapsedFolders + k) },
                )
            }
        }
    }

    private fun gone(text: String) = assertTrue("'$text' should not be listed", compose.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty())

    @Test
    fun openIsDefaultAndHistoryIsOneTapAway() {
        show()
        compose.onNodeWithText("Agent Deck build").assertIsDisplayed()
        gone("Guardian check")
        compose.onNodeWithText("All chats").performClick()
        assertEquals(BrowserScope.ALL, ui.scope)
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Guardian check"))
        compose.onNodeWithText("Guardian check").assertIsDisplayed()
    }

    @Test
    fun searchRevealsChildWithParentAndClearRestoresCollapsedState() {
        show()
        compose.onNode(hasSetTextAction()).performTextInput("parfit")
        compose.onNodeWithText("Refactor services").assertIsDisplayed() // real parent as context
        compose.onNodeWithText("Parfit").assertIsDisplayed()
        gone("Agent Deck build")
        assertTrue(ui.expanded.isEmpty()) // search does not touch saved disclosure
        compose.onNodeWithContentDescription("Clear search").performClick()
        assertEquals("", ui.query)
        gone("Parfit")
        compose.onNodeWithText("Agent Deck build").assertIsDisplayed()
    }

    @Test
    fun noResultsOffersHistoryAndClearSearch() {
        ui = ui.copy(query = "backend auth")
        show()
        compose.onNodeWithText("Show 1 in history").performClick()
        assertEquals(BrowserScope.ALL, ui.scope)
        compose.onNodeWithText("Review backend auth").assertIsDisplayed()
    }

    @Test
    fun providerFilterAndClearFilters() {
        show()
        compose.onNodeWithText("All providers").performClick()
        compose.onNodeWithText("Codex").performClick()
        assertEquals("codex", ui.filter)
        gone("Szillus copy edits")
        compose.onNode(hasSetTextAction()).performTextInput("szillus")
        compose.onNodeWithText("Clear filters (1 hidden)").performClick()
        assertEquals("all", ui.filter)
        compose.onNodeWithText("Szillus copy edits").assertIsDisplayed()
    }

    @Test
    fun folderCollapsesAndChildDisclosureExpands() {
        show()
        compose.onAllNodesWithText("1 open child agent · 1 active").onFirst().performClick()
        compose.onNodeWithText("Search model docs").assertIsDisplayed()
        compose.onNodeWithText("szillus").performClick()
        assertEquals(setOf("/Users/test/sites/szillus"), ui.collapsedFolders)
        gone("Szillus copy edits")
    }

    @Test
    fun selectedChatIsMarked() {
        ui = ui.copy(selectedId = Fixtures.claudeParent.id)
        show()
        compose.onNode(hasText("Agent Deck build") and isSelected()).assertIsDisplayed().assertIsSelected()
        assertTrue(compose.onAllNodes(hasText("Szillus copy edits") and isSelected()).fetchSemanticsNodes().isEmpty())
    }
}
