package de.finn.agentdeck.core.api

import de.finn.agentdeck.core.model.PairRequest
import de.finn.agentdeck.core.model.SessionStatus
import de.finn.agentdeck.core.model.SettingsPatch
import de.finn.agentdeck.core.model.StartSessionRequest
import de.finn.agentdeck.core.model.TerminalKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class AgentDeckClientTest {
    private lateinit var server: MockWebServer
    private lateinit var trustingHttp: OkHttpClient
    private lateinit var client: AgentDeckClient
    private var token: String? = "device-token-123"

    @Before
    fun setUp() {
        server = MockWebServer()
        val cert = HeldCertificate.Builder().addSubjectAlternativeName("127.0.0.1").build()
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(cert).build().sslSocketFactory())
        server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
        // Trust exactly the test CA, the way a real server is trusted through the platform store.
        val clientCerts = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        trustingHttp = AgentDeckClient.defaultHttpClient().newBuilder()
            .sslSocketFactory(clientCerts.sslSocketFactory(), clientCerts.trustManager).build()
        val base = server.url("/").newBuilder().host("127.0.0.1").build().toString()
        client = AgentDeckClient(ServerUrl.parse(base).getOrThrow(), { token }, trustingHttp)
    }

    @After
    fun tearDown() = server.close()

    private fun json(body: String, code: Int = 200) =
        MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()

    @Test
    fun sessionsUseBearerTokenAndTolerateUnknownFieldsAndStatuses() = runBlocking {
        server.enqueue(
            json(
                """{"sessions":[{"id":"s1","agent":"claude","nativeId":"n1","title":"Fix","cwd":"/x","model":null,
                "status":"brand_new_status","stage":null,"progress":null,"updatedAt":"2026-10-06T20:00:00Z","managed":true,
                "capabilities":{"send":true,"interrupt":true,"approve":true,"stop":true},"lastMessage":"hi","unread":2,
                "futureField":{"a":1}}]}""",
            ),
        )
        val result = client.sessions()
        val req = server.takeRequest()
        assertEquals("Bearer device-token-123", req.headers["Authorization"])
        assertEquals("/api/v1/sessions?agent=all&scope=all", req.target)
        val s = result.sessions.single()
        assertEquals(SessionStatus.UNKNOWN, s.status)
        assertNull(s.progress)
        assertEquals(2, s.unread)
    }

    @Test
    fun sendAlwaysInterruptsWithIdempotentRequestIdAndEncodesSessionId() = runBlocking {
        server.enqueue(json("""{"accepted":true,"sessionId":"a/b c"}"""))
        val r = client.send("a/b c", "line1\nline2; rm -rf ~", "req-42")
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/v1/sessions/a%2Fb%20c/send", req.target)
        val body = req.body!!.utf8()
        assertTrue(body, body.contains("\"interrupt\":true"))
        assertTrue(body.contains("\"requestId\":\"req-42\""))
        assertTrue("text is sent verbatim as JSON data", body.contains("line1\\nline2; rm -rf ~"))
        assertTrue(r.accepted)
    }

    @Test
    fun pairDoesNotSendAuthorization() = runBlocking {
        token = null
        server.enqueue(json("""{"deviceId":"d1","token":"t","pushKey":"AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=","serverName":"Mac"}"""))
        val r = client.pair(PairRequest("123456", "Fold", null))
        val req = server.takeRequest()
        assertNull(req.headers["Authorization"])
        assertEquals("/api/v1/pair", req.target)
        assertTrue(req.body!!.utf8().contains("\"deviceName\":\"Fold\""))
        assertEquals("d1", r.deviceId)
    }

    @Test
    fun errorEnvelopeIsSurfaced() = runBlocking {
        server.enqueue(json("""{"error":{"code":"stale_approval","message":"The prompt changed."}}""", 409))
        try {
            client.respondApproval("s1", "ap1", "yes", "r1")
            fail("expected error")
        } catch (e: ApiException.Http) {
            assertEquals(409, e.status)
            assertEquals("stale_approval", e.code)
            assertEquals("The prompt changed.", e.message)
        }
        assertEquals("/api/v1/sessions/s1/approvals/ap1", server.takeRequest().target)
    }

    @Test
    fun unauthorizedIsDistinct() = runBlocking {
        server.enqueue(json("""{"error":{"code":"unauthorized","message":"Unknown device"}}""", 401))
        try {
            client.models()
            fail("expected error")
        } catch (e: ApiException.Unauthorized) {
            assertEquals("Unknown device", e.message)
        }
    }

    @Test
    fun nonJsonErrorFallsBackToReadableMessage() = runBlocking {
        server.enqueue(MockResponse.Builder().code(502).body("<html>bad gateway</html>").build())
        try {
            client.settings()
            fail("expected error")
        } catch (e: ApiException.Http) {
            assertEquals(502, e.status)
            assertTrue(e.isTransient)
        }
    }

    @Test
    fun malformedSuccessIsProtocolError() = runBlocking {
        server.enqueue(json("""{"sessions":"nope"}"""))
        try {
            client.sessions()
            fail("expected error")
        } catch (e: ApiException.Protocol) {
            // expected
        }
    }

    @Test
    fun untrustedCertificateIsRejectedNotBypassed() = runBlocking {
        val strict = AgentDeckClient(client.server, { token }) // platform trust store only
        try {
            strict.sessions()
            fail("a self-signed certificate must not be accepted")
        } catch (e: ApiException.Tls) {
            // expected
        } catch (e: ApiException.Network) {
            assertTrue("TLS failure expected, got ${e.cause}", e.cause is javax.net.ssl.SSLException)
        }
    }

    @Test
    fun otherEndpointsHitContractPaths() = runBlocking {
        repeat(9) { server.enqueue(json("""{"accepted":true,"session":{"id":"n","agent":"codex"},"settings":{},"agents":[],"messages":[],"approvals":[],"text":"x"}""")) }
        client.startSession(StartSessionRequest("codex", "exact-model-id", "/w", "go"))
        client.stop("s1", "r")
        client.inputKey("s1", TerminalKey.ESCAPE)
        client.refreshModels()
        client.patchSettings(SettingsPatch(progressIntervalSeconds = 20))
        client.updateFcmToken("dev 1", "tok")
        client.messages("s1", 50)
        client.terminal("s1")
        client.approvals("s1")
        val got = List(9) { server.takeRequest().let { "${it.method} ${it.target} ${it.body?.utf8().orEmpty()}" } }
        assertTrue(got[0], got[0].startsWith("POST /api/v1/sessions {") && got[0].contains("\"model\":\"exact-model-id\""))
        assertEquals("POST /api/v1/sessions/s1/stop {\"requestId\":\"r\"}", got[1])
        assertEquals("POST /api/v1/sessions/s1/input {\"key\":\"escape\"}", got[2])
        assertTrue(got[3].startsWith("POST /api/v1/models/refresh"))
        assertEquals("PATCH /api/v1/settings {\"progressIntervalSeconds\":20}", got[4])
        assertEquals("PUT /api/v1/devices/dev%201/fcm-token {\"fcmToken\":\"tok\"}", got[5])
        assertEquals("GET /api/v1/sessions/s1/messages?limit=50 ", got[6])
        assertEquals("GET /api/v1/sessions/s1/terminal ", got[7])
        assertEquals("GET /api/v1/sessions/s1/approvals ", got[8])
    }

    @Test
    fun eventStreamDeliversUpdatesAndReconnects() = runBlocking {
        server.enqueue(
            MockResponse.Builder().addHeader("Content-Type", "text/event-stream")
                .body(": hi\n\nevent: update\ndata: {\"type\":\"sessions\"}\n\n").build(),
        )
        server.enqueue(
            MockResponse.Builder().addHeader("Content-Type", "text/event-stream")
                .body("event: update\ndata: {\"type\":\"messages\",\"sessionId\":\"s9\"}\n\n").build(),
        )
        val updates = withTimeout(15_000) {
            EventStream(client).signals().filterIsInstance<StreamSignal.Update>().take(2).toList()
        }
        assertEquals("sessions", updates[0].event.type)
        assertEquals("s9", updates[1].event.sessionId)
        val req = server.takeRequest()
        assertEquals("text/event-stream", req.headers["Accept"])
        assertEquals("Bearer device-token-123", req.headers["Authorization"])
    }

    @Test
    fun eventStreamStopsOnUnauthorized() = runBlocking {
        server.enqueue(json("""{"error":{"code":"unauthorized","message":"gone"}}""", 401))
        val d = withTimeout(10_000) { EventStream(client).signals().filterIsInstance<StreamSignal.Disconnected>().first() }
        assertTrue(d.fatal)
        assertTrue(d.error is ApiException.Unauthorized)
    }
}
