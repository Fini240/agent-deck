package de.finn.agentdeck.ui.detail

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.finn.agentdeck.core.model.Message
import de.finn.agentdeck.core.model.SessionStatus
import de.finn.agentdeck.ui.Fixtures
import de.finn.agentdeck.ui.theme.AgentDeckTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Readable assistant text (light Markdown, code copy) and the empty-draft-only quick replies. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w390dp-h844dp")
class ChatContentTest {
    @get:Rule val compose = createComposeRule()

    private val idleParent = Fixtures.claudeParent.copy(status = SessionStatus.IDLE, stage = null)
    private val markdown = "## Plan\n- **Fix** the `auth` check\n- run tests\n\n```kotlin\nval token = load()\nverify(token)\n```\nDone."

    private fun show(ui: DetailUi, actions: DetailActions = DetailActions()) {
        compose.setContent { AgentDeckTheme { SessionDetailPane(ui, actions) } }
    }

    private fun chat(vararg messages: Message, draft: String = "", session: de.finn.agentdeck.core.model.Session = idleParent) =
        DetailUi(session = session, messages = messages.toList(), messagesLoaded = true, draft = draft)

    private fun gone(text: String, substring: Boolean = false) =
        assertTrue("'$text' should not be shown", compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isEmpty())

    @Test
    fun assistantMarkdownRendersWithoutMarkers() {
        show(chat(Message("a1", "assistant", markdown, "2026-10-07T09:00:00Z")))
        compose.onNodeWithText("Plan").assertIsDisplayed()
        compose.onNodeWithText("Fix the auth check").assertIsDisplayed()
        compose.onNodeWithText("val token = load()\nverify(token)").assertIsDisplayed()
        compose.onNodeWithText("kotlin").assertIsDisplayed()
        gone("**", substring = true)
        gone("```", substring = true)
        gone("## Plan")
    }

    @Test
    fun codeCopyPutsOnlyTheCodeOnTheClipboard() {
        show(chat(Message("a1", "assistant", markdown, "2026-10-07T09:00:00Z")))
        compose.onNodeWithContentDescription("Copy kotlin code").performClick()
        compose.waitForIdle()
        val cm = ApplicationProvider.getApplicationContext<Context>().getSystemService(ClipboardManager::class.java)
        assertEquals("val token = load()\nverify(token)", cm.primaryClip?.getItemAt(0)?.text?.toString())
        compose.onNodeWithText("Copied").assertIsDisplayed()
    }

    @Test
    fun eachCodeBlockCopiesItsOwnContent() {
        val copied = mutableListOf<String>()
        compose.setContent {
            AgentDeckTheme { MessageContent("first:\n```\none\n```\nsecond:\n```sh\ntwo --flag\n```", onCopy = { copied += it }) }
        }
        compose.onNodeWithContentDescription("Copy code").performClick()
        compose.onNodeWithContentDescription("Copy sh code").performClick()
        assertEquals(listOf("one", "two --flag"), copied)
    }

    @Test
    fun userMessagesStayPlainAndToolsStayCollapsed() {
        show(
            chat(
                Message("u1", "user", "please keep **these** `markers`", "2026-10-07T09:00:00Z"),
                Message("t1", "tool", "Bash(ls)\n## not a heading", "2026-10-07T09:00:05Z", "Bash"),
            ),
        )
        compose.onNodeWithText("please keep **these** `markers`").assertIsDisplayed()
        gone("## not a heading", substring = true)
        compose.onNodeWithText("Show output (2 lines)").assertIsDisplayed()
    }

    @Test
    fun quickReplyFillsAnEmptyDraftWithoutSending() {
        val drafts = mutableListOf<String>()
        var sent = 0
        show(chat(*Fixtures.parentMessages.toTypedArray()), DetailActions(onDraft = { drafts += it }, onSend = { sent++ }))
        compose.onNodeWithText("Quick reply").assertIsEnabled().performClick()
        compose.onNodeWithText("Summarize what changed and what is left.").performClick()
        assertEquals(listOf("Summarize what changed and what is left."), drafts)
        assertEquals(0, sent)
        gone("Give me a short progress update.")
    }

    @Test
    fun quickReplyIsHiddenOnceTheDraftHasText() {
        show(chat(*Fixtures.parentMessages.toTypedArray(), draft = "my own words"))
        gone("Quick reply")
        assertNull(quickReplyDraft("my own words", QUICK_REPLIES[0]))
        assertNull(quickReplyDraft(" ", QUICK_REPLIES[0]))
        assertEquals(QUICK_REPLIES[1], quickReplyDraft("", QUICK_REPLIES[1]))
    }

    @Test
    fun quickReplyDisabledWhileAnActionIsBusy() {
        show(chat(*Fixtures.parentMessages.toTypedArray()).copy(busyAction = "stop"))
        compose.onNodeWithText("Quick reply").assertIsNotEnabled()
    }

    @Test
    fun noQuickReplyForReadOnlyOrChildChats() {
        show(DetailUi(session = Fixtures.workingChild, parent = Fixtures.claudeParent, messages = Fixtures.childMessages, messagesLoaded = true))
        gone("Quick reply")
    }

    @Test
    fun noQuickReplyForResumableHistory() {
        val codex = Fixtures.sessions.first { it.id == "codex:01a10000-0000-7000-8000-000000000001" }
        show(DetailUi(session = codex, messages = Fixtures.parentMessages.take(2), messagesLoaded = true))
        gone("Quick reply")
    }
}
