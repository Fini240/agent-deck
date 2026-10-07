package de.finn.agentdeck.core.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ParsingTest {
    @Test
    fun serverUrlRequiresHttps() {
        assertTrue(ServerUrl.parse("http://mac.ts.net:8443").isFailure)
        assertTrue(ServerUrl.parse("ftp://mac").isFailure)
        assertTrue(ServerUrl.parse("").isFailure)
        assertTrue(ServerUrl.parse("https://user:pw@mac.ts.net").isFailure)
        assertTrue(ServerUrl.parse("https://mac.ts.net/?x=1").isFailure)
        assertEquals(
            "https://mac.example.ts.net:8443",
            ServerUrl.parse(" mac.example.ts.net:8443/ ").getOrThrow().value,
        )
        assertEquals("https://mac.ts.net/deck", ServerUrl.parse("HTTPS://mac.ts.net/deck/").getOrThrow().value)
    }

    @Test
    fun pairingLinkParsesContractUri() {
        val link = PairingLink.parse(
            "agentdeck://pair?server=https%3A%2F%2Fmac.example.ts.net%3A8443&code=AB12-CD34",
        ).getOrThrow()
        assertEquals("https://mac.example.ts.net:8443", link.server.value)
        assertEquals("AB12-CD34", link.code)
    }

    @Test
    fun pairingLinkRejectsInsecureOrForeignCodes() {
        assertTrue(PairingLink.parse("agentdeck://pair?server=http%3A%2F%2Fevil&code=1").isFailure)
        assertTrue(PairingLink.parse("https://example.com/?server=https://x&code=1").isFailure)
        assertTrue(PairingLink.parse("agentdeck://pair?server=https%3A%2F%2Fx").isFailure)
        assertTrue(PairingLink.parse("not a uri at all %%%").isFailure)
    }

    @Test
    fun sseParserHandlesCommentsMultilineAndDefaults() {
        val p = SseParser()
        assertNull(p.feed(": keepalive"))
        assertNull(p.feed(""))
        assertNull(p.feed("event: update"))
        assertNull(p.feed("data: {\"a\":"))
        assertNull(p.feed("data:1}"))
        assertNull(p.feed("id: 7"))
        val e = p.feed("")!!
        assertEquals("update", e.event)
        assertEquals("{\"a\":\n1}", e.data)
        assertEquals("7", e.id)
        p.feed("data: x")
        assertEquals("message", p.feed("")!!.event)
    }

    @Test
    fun backoffGrowsAndCaps() {
        val r = Random(1)
        val d0 = Backoff.delayFor(0, r)
        val d3 = Backoff.delayFor(3, r)
        val d20 = Backoff.delayFor(20, r)
        assertTrue(d0 in 500..1200)
        assertTrue(d3 in 6400..9600)
        assertTrue(d20 <= 30_000)
    }
}
