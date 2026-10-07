package de.finn.agentdeck.core.model

import de.finn.agentdeck.core.model.ChatBlock.Bullet
import de.finn.agentdeck.core.model.ChatBlock.Code
import de.finn.agentdeck.core.model.ChatBlock.Heading
import de.finn.agentdeck.core.model.ChatBlock.Paragraph
import de.finn.agentdeck.core.model.ChatBlock.Quote
import de.finn.agentdeck.core.model.ChatBlock.Rule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatTextTest {
    private fun p(text: String) = Paragraph(listOf(InlineSpan(text)))

    @Test
    fun plainTextIsOneParagraphPerBlankLineSeparatedRun() {
        assertEquals(listOf(p("first line\nsecond line"), p("next para")), ChatText.parse("first line\nsecond line\n\n\nnext para"))
        assertEquals(emptyList<ChatBlock>(), ChatText.parse(""))
        assertEquals(listOf(p("crlf\nline")), ChatText.parse("crlf\r\nline"))
    }

    @Test
    fun fencedCodeKeepsExactContentAndLanguage() {
        val text = "Run this:\n```kotlin\nval x = \"**not bold**\"\n\n  indented `tick`\n```\nDone."
        val blocks = ChatText.parse(text)
        assertEquals(p("Run this:"), blocks[0])
        assertEquals(Code("kotlin", "val x = \"**not bold**\"\n\n  indented `tick`", closed = true), blocks[1])
        assertEquals(p("Done."), blocks[2])
    }

    @Test
    fun fenceVariants() {
        assertEquals(listOf(Code(null, "a", true)), ChatText.parse("~~~\na\n~~~"))
        // A longer opening fence is only closed by a fence at least as long, so ``` inside stays code.
        assertEquals(listOf(Code("md", "```\ninner\n```", true)), ChatText.parse("````md\n```\ninner\n```\n````"))
        // Streaming reply: fence not closed yet, everything after it is still code.
        val open = ChatText.parse("Patch:\n```diff\n+ added\n- removed")
        assertEquals(Code("diff", "+ added\n- removed", closed = false), open.last())
        assertFalse((open.last() as Code).closed)
    }

    @Test
    fun headingsBulletsQuotesAndRules() {
        val blocks = ChatText.parse(
            """
            ## Summary ##
            - first
            * second
              continued
                - nested
            3. third
            > quoted
            > more
            ---
            #hashtag stays text
            """.trimIndent(),
        )
        assertEquals(
            listOf(
                Heading(2, listOf(InlineSpan("Summary"))),
                Bullet("•", 0, listOf(InlineSpan("first"))),
                Bullet("•", 0, listOf(InlineSpan("second\ncontinued"))),
                Bullet("•", 2, listOf(InlineSpan("nested"))),
                Bullet("3.", 0, listOf(InlineSpan("third"))),
                Quote(listOf(InlineSpan("quoted\nmore"))),
                Rule,
                p("#hashtag stays text"),
            ),
            blocks,
        )
    }

    @Test
    fun boldLineStartIsNotABullet() {
        val blocks = ChatText.parse("**Note:** careful")
        assertEquals(listOf(Paragraph(listOf(InlineSpan("Note:", strong = true), InlineSpan(" careful")))), blocks)
    }

    @Test
    fun inlineCodeAndStrong() {
        assertEquals(
            listOf(InlineSpan("Run "), InlineSpan("gradle test", code = true), InlineSpan(" then "), InlineSpan("ship it", strong = true), InlineSpan(".")),
            ChatText.inline("Run `gradle test` then **ship it**."),
        )
        // Strong around code; markers inside code are literal.
        assertEquals(
            listOf(InlineSpan("use ", strong = true), InlineSpan("a**b", strong = true, code = true), InlineSpan(" now", strong = true)),
            ChatText.inline("**use `a**b` now**"),
        )
        // Double-backtick span can contain a single backtick.
        assertEquals(listOf(InlineSpan("a`b", code = true)), ChatText.inline("`` a`b ``"))
    }

    @Test
    fun unmatchedMarkersStayLiteral() {
        for (s in listOf("2 ** 8 = 256", "a **dangling", "`unterminated code", "snake __init__ name", "***", "** not bold**", "x * y")) {
            val spans = ChatText.inline(s)
            assertEquals(s, ChatText.plain(spans))
            assertTrue("no styling in '$s'", spans.none { it.strong || it.code })
        }
    }

    @Test
    fun noTextIsLostOutsideMarkup() {
        val text = "Changed **3 files**:\n- `App.kt`: fixed\n- docs\n\n```\nok\n```"
        val visible = ChatText.parse(text).joinToString("|") {
            when (it) {
                is Paragraph -> ChatText.plain(it.spans)
                is Bullet -> it.marker + ChatText.plain(it.spans)
                is Code -> it.code
                is Heading -> ChatText.plain(it.spans)
                is Quote -> ChatText.plain(it.spans)
                Rule -> "-"
            }
        }
        assertEquals("Changed 3 files:|•App.kt: fixed|•docs|ok", visible)
    }
}
