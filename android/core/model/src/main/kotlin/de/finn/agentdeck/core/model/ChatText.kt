package de.finn.agentdeck.core.model

/**
 * Deliberately small Markdown subset for agent chat text: fenced code, headings, bullets,
 * quotes, rules, and inline `code` / **strong**. Everything else stays literal, so nothing an
 * agent wrote is ever hidden; unknown or unclosed syntax degrades to the original characters.
 */
sealed interface ChatBlock {
    data class Paragraph(val spans: List<InlineSpan>) : ChatBlock
    data class Heading(val level: Int, val spans: List<InlineSpan>) : ChatBlock
    /** [marker] is "•" for unordered items, the original "3." / "3)" for ordered ones. */
    data class Bullet(val marker: String, val indent: Int, val spans: List<InlineSpan>) : ChatBlock
    data class Quote(val spans: List<InlineSpan>) : ChatBlock
    /** [code] is exactly the lines between the fences; [closed] is false while a reply is still streaming. */
    data class Code(val language: String?, val code: String, val closed: Boolean = true) : ChatBlock
    data object Rule : ChatBlock
}

data class InlineSpan(val text: String, val strong: Boolean = false, val code: Boolean = false)

object ChatText {
    private val FENCE = Regex("""^ {0,3}(`{3,}|~{3,})\s*([^`\s]*).*$""")
    private val HEADING = Regex("""^ {0,3}(#{1,6})\s+(.*?)(?:\s+#+)?\s*$""")
    private val BULLET = Regex("""^(\s*)([-*+]|\d{1,9}[.)])\s+(.*)$""")
    private val QUOTE = Regex("""^ {0,3}>\s?(.*)$""")
    private val RULE = Regex("""^ {0,3}([-*_])(\s*\1){2,}\s*$""")
    private const val MAX_INDENT = 3

    fun parse(text: String): List<ChatBlock> {
        val lines = text.replace("\r\n", "\n").split('\n')
        val out = mutableListOf<ChatBlock>()
        val para = mutableListOf<String>()
        val quote = mutableListOf<String>()
        var bullet: Triple<String, Int, MutableList<String>>? = null

        fun flush() {
            if (para.isNotEmpty()) out += ChatBlock.Paragraph(inline(para.joinToString("\n"))).also { para.clear() }
            if (quote.isNotEmpty()) out += ChatBlock.Quote(inline(quote.joinToString("\n"))).also { quote.clear() }
            bullet?.let { (m, i, l) -> out += ChatBlock.Bullet(m, i, inline(l.joinToString("\n"))) }
            bullet = null
        }

        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val fence = FENCE.matchEntire(line)
            if (fence != null) {
                flush()
                val marker = fence.groupValues[1]
                val body = mutableListOf<String>()
                var closed = false
                i++
                while (i < lines.size) {
                    val t = lines[i].trim()
                    if (t.length >= marker.length && t.all { it == marker[0] }) { closed = true; break }
                    body += lines[i]
                    i++
                }
                out += ChatBlock.Code(fence.groupValues[2].ifBlank { null }, body.joinToString("\n"), closed)
                i++
                continue
            }
            when {
                line.isBlank() -> flush()
                RULE.matches(line) -> { flush(); out += ChatBlock.Rule }
                HEADING.matchEntire(line) != null -> {
                    flush()
                    val m = HEADING.matchEntire(line)!!
                    out += ChatBlock.Heading(m.groupValues[1].length, inline(m.groupValues[2]))
                }
                BULLET.matchEntire(line) != null -> {
                    flush()
                    val m = BULLET.matchEntire(line)!!
                    val raw = m.groupValues[2]
                    val indent = (m.groupValues[1].replace("\t", "    ").length / 2).coerceAtMost(MAX_INDENT)
                    bullet = Triple(if (raw[0].isDigit()) raw else "•", indent, mutableListOf(m.groupValues[3]))
                }
                QUOTE.matchEntire(line) != null -> {
                    if (para.isNotEmpty() || bullet != null) flush()
                    quote += QUOTE.matchEntire(line)!!.groupValues[1]
                }
                // An indented line right after a bullet continues that item rather than starting a paragraph.
                bullet != null && line.startsWith("  ") -> bullet!!.third += line.trim()
                else -> {
                    if (quote.isNotEmpty() || bullet != null) flush()
                    para += line
                }
            }
            i++
        }
        flush()
        return out
    }

    /** Inline `code` (any backtick run length) and **strong**; unmatched markers stay literal. */
    fun inline(text: String): List<InlineSpan> {
        val out = mutableListOf<InlineSpan>()
        val buf = StringBuilder()
        var strong = false
        fun emit(s: String, code: Boolean = false) {
            if (s.isEmpty()) return
            val last = out.lastOrNull()
            if (last != null && last.strong == strong && last.code == code) out[out.lastIndex] = last.copy(text = last.text + s)
            else out += InlineSpan(s, strong, code)
        }
        fun flushBuf() { emit(buf.toString()); buf.clear() }

        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '`') {
                val run = runLength(text, i, '`')
                val close = findBacktickClose(text, i + run, run)
                if (close >= 0) {
                    flushBuf()
                    var content = text.substring(i + run, close)
                    // CommonMark: one surrounding space is stripped so `` `x` `` can hold a backtick.
                    if (content.length >= 2 && content.startsWith(' ') && content.endsWith(' ') && content.isNotBlank()) content = content.substring(1, content.length - 1)
                    emit(content, code = true)
                    i = close + run
                } else {
                    buf.append(text, i, i + run)
                    i += run
                }
                continue
            }
            if (c == '*' && text.startsWith("**", i)) {
                if (strong) {
                    if (i > 0 && !text[i - 1].isWhitespace()) { flushBuf(); strong = false; i += 2; continue }
                    buf.append("**"); i += 2; continue
                }
                val after = text.getOrNull(i + 2)
                if (after != null && !after.isWhitespace() && after != '*' && findStrongClose(text, i + 2) >= 0) {
                    flushBuf(); strong = true; i += 2; continue
                }
            }
            buf.append(c)
            i++
        }
        flushBuf()
        return out
    }

    /** Plain text of [spans], i.e. what the reader sees with all markers removed. */
    fun plain(spans: List<InlineSpan>): String = spans.joinToString("") { it.text }

    private fun runLength(s: String, from: Int, ch: Char): Int {
        var j = from
        while (j < s.length && s[j] == ch) j++
        return j - from
    }

    private fun findBacktickClose(s: String, from: Int, run: Int): Int {
        var j = from
        while (j < s.length) {
            if (s[j] == '`') {
                val r = runLength(s, j, '`')
                if (r == run) return j
                j += r
            } else j++
        }
        return -1
    }

    /** A closing `**` that isn't hidden inside a code span and doesn't follow whitespace. */
    private fun findStrongClose(s: String, from: Int): Int {
        var j = from
        while (j < s.length) {
            when {
                s[j] == '`' -> {
                    val r = runLength(s, j, '`')
                    val close = findBacktickClose(s, j + r, r)
                    j = if (close >= 0) close + r else j + r
                }
                s.startsWith("**", j) && j > from && !s[j - 1].isWhitespace() -> return j
                else -> j++
            }
        }
        return -1
    }
}
