package de.finn.agentdeck.ui.detail

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.finn.agentdeck.core.model.Message
import de.finn.agentdeck.core.model.SessionStatus
import de.finn.agentdeck.ui.Fixtures
import de.finn.agentdeck.ui.theme.AgentDeckTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Reading and replying in the detail pane: composer states, read-only paths, tool output, scroll-follow. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w390dp-h844dp")
class UiReplyTest {
    @get:Rule val compose = createComposeRule()

    private val parent = Fixtures.claudeParent
    private val idleParent = Fixtures.claudeParent.copy(status = SessionStatus.IDLE, stage = null)

    private fun show(ui: DetailUi, actions: DetailActions = DetailActions()) {
        compose.setContent { AgentDeckTheme { SessionDetailPane(ui, actions) } }
    }

    private fun gone(text: String, substring: Boolean = false) =
        assertTrue("'$text' should not be shown", compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isEmpty())

    @Test
    fun emptyDraftDisablesSendAndPlaceholderNamesProvider() {
        show(DetailUi(session = idleParent, messages = Fixtures.parentMessages, messagesLoaded = true, draft = ""))
        compose.onNodeWithText("Message Claude Code").assertIsDisplayed()
        compose.onNodeWithText("Send").assertIsNotEnabled()
        gone("Interrupts current work")
    }

    @Test
    fun workingSessionSendsWithInterruptHint() {
        var sent = 0
        show(DetailUi(session = parent, messages = Fixtures.parentMessages, messagesLoaded = true, draft = "next step"), DetailActions(onSend = { sent++ }))
        compose.onNodeWithText("Interrupts current work").assertIsDisplayed()
        compose.onNodeWithText("Send").assertIsEnabled().performClick()
        assertEquals(1, sent)
    }

    @Test
    fun sendingLocksDraftAndButton() {
        var sent = 0
        show(DetailUi(session = parent, messages = Fixtures.parentMessages, messagesLoaded = true, draft = "next step", sending = true), DetailActions(onSend = { sent++ }))
        compose.onNodeWithText("Sending…").assertIsDisplayed()
        compose.onNodeWithText("Send").assertIsNotEnabled()
        compose.onNodeWithText("next step").assertIsNotEnabled()
        assertEquals(0, sent)
    }

    @Test
    fun failedSendKeepsDraftVisibleAndOffersRetry() {
        var sent = 0
        show(
            DetailUi(session = parent, messages = Fixtures.parentMessages, messagesLoaded = true, draft = "keep this text", sendError = "Can't reach the Mac."),
            DetailActions(onSend = { sent++ }),
        )
        compose.onNodeWithText("Not sent: Can't reach the Mac.", substring = true).assertIsDisplayed()
        compose.onNodeWithText("keep this text").assertIsDisplayed()
        compose.onNodeWithText("Retry").performClick()
        assertEquals(1, sent)
    }

    @Test
    fun childIsReadOnlyAndOpensRealParent() {
        var opened: String? = null
        show(
            DetailUi(session = Fixtures.workingChild, parent = parent, messages = Fixtures.childMessages, messagesLoaded = true, now = Fixtures.now),
            DetailActions(onOpenSession = { opened = it }),
        )
        compose.onNodeWithText("Child agents are inspect-only", substring = true).assertIsDisplayed()
        gone("Send")
        assertTrue(compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isEmpty())
        compose.onNodeWithText("Open parent chat").performClick()
        assertEquals(parent.id, opened)
    }

    @Test
    fun childWithoutKnownParentHasNoParentButton() {
        show(DetailUi(session = Fixtures.workingChild, parent = null, messages = Fixtures.childMessages, messagesLoaded = true))
        compose.onNodeWithText("isn't in the current session list", substring = true).assertIsDisplayed()
        gone("Open parent chat")
        gone("Send")
    }

    @Test
    fun resumableHistoryIsReadOnlyWithResume() {
        val codex = Fixtures.sessions.first { it.id == "codex:01a10000-0000-7000-8000-000000000001" }
        var resumed = 0
        show(DetailUi(session = codex, messages = Fixtures.parentMessages.take(2), messagesLoaded = true), DetailActions(onResume = { resumed++ }))
        compose.onNodeWithText("Read-only", substring = true).assertIsDisplayed()
        gone("Send")
        compose.onNodeWithText("Resume on the Mac").performClick()
        assertEquals(1, resumed)
    }

    @Test
    fun toolOutputStartsCollapsedAndExpands() {
        show(DetailUi(session = parent, messages = Fixtures.parentMessages, messagesLoaded = true))
        compose.onNodeWithText("Tool · Agent").assertIsDisplayed()
        gone("→ started a0000000000000003", substring = true)
        compose.onNodeWithText("Show output (2 lines)").performClick()
        compose.onNodeWithText("→ started a0000000000000003", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Hide output").performClick()
        gone("→ started a0000000000000003", substring = true)
    }

    @Test
    fun sessionInfoDisclosesFullMetadata() {
        show(DetailUi(session = parent, messages = Fixtures.parentMessages, messagesLoaded = true))
        gone("/Users/test/proj")
        compose.onNodeWithContentDescription("Session info").performClick()
        compose.onNodeWithText("/Users/test/proj").assertIsDisplayed()
        compose.onNodeWithText("opus").assertIsDisplayed()
        compose.onNodeWithContentDescription("Hide session info").performClick()
        gone("/Users/test/proj")
    }

    @Test
    fun stopConfirmationDoesNotFollowToAnotherChat() {
        var ui by mutableStateOf(DetailUi(session = parent, messages = Fixtures.parentMessages, messagesLoaded = true))
        var stopped = 0
        compose.setContent { AgentDeckTheme { SessionDetailPane(ui, DetailActions(onStop = { stopped++ })) } }
        compose.onNodeWithText("Stop").performClick()
        compose.onNodeWithText("Stop current work?").assertIsDisplayed()
        ui = ui.copy(session = parent.copy(id = "claude:other", title = "Other chat"))
        compose.waitForIdle()
        gone("Stop current work?")
        assertEquals(0, stopped)
    }

    @Test
    fun newMessagesDoNotYankReaderAndJumpToLatestWorks() {
        val many = (1..40).map { Message("m$it", if (it % 2 == 0) "assistant" else "user", "Message number $it", "2026-10-07T09:0${it % 10}:00Z") }
        var ui by mutableStateOf(DetailUi(session = idleParent, messages = many, messagesLoaded = true))
        compose.setContent { AgentDeckTheme { SessionDetailPane(ui, DetailActions()) } }

        // Initial load follows the bottom.
        compose.onNodeWithText("Message number 40").assertIsDisplayed()
        gone("Jump to latest")

        // Reader scrolls up: the list stays there and offers a way back.
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        compose.onNodeWithText("Jump to latest").assertIsDisplayed()

        // A new message arrives: no forced scroll, the button says there is something new.
        ui = ui.copy(messages = many + Message("m41", "assistant", "Message number 41", "2026-10-07T09:12:00Z"))
        compose.waitForIdle()
        compose.onNodeWithText("Message number 1").assertIsDisplayed()
        compose.onNodeWithText("New messages").performClick()
        compose.onNodeWithText("Message number 41").assertIsDisplayed()
        gone("New messages")
        gone("Jump to latest")

        // While at the bottom, the next message is followed automatically.
        ui = ui.copy(messages = ui.messages + Message("m42", "assistant", "Message number 42", "2026-10-07T09:12:30Z"))
        compose.waitForIdle()
        compose.onNodeWithText("Message number 42").assertIsDisplayed()
        gone("Jump to latest")

        // Streaming can extend the same message without changing its id or the list size.
        val streaming = ui.messages.last().copy(text = (1..70).joinToString("\n") { "Streaming line $it" })
        ui = ui.copy(messages = ui.messages.dropLast(1) + streaming)
        compose.waitForIdle()
        gone("Jump to latest")
    }
}
