package de.finn.agentdeck.core.api

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class ApkUpdatesTest {
    private lateinit var server: MockWebServer
    private lateinit var client: ApkUpdateClient
    private val bytes = "test APK bytes".toByteArray()
    private val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private val offer get() = ApkOffer(true, "0.2.2", bytes.size.toLong(), sha, "/apk")
    @Before fun setup() {
        server = MockWebServer()
        val cert = HeldCertificate.Builder().addSubjectAlternativeName("127.0.0.1").build()
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(cert).build().sslSocketFactory())
        server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        client = ApkUpdateClient(ServerUrl.trusted(server.url("/").newBuilder().host("127.0.0.1").build().toString()),
            AgentDeckClient.defaultHttpClient().newBuilder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager).build())
    }
    @After fun close() = server.close()
    private fun response(body: String, code: Int = 200) = server.enqueue(MockResponse.Builder().code(code).body(body).build())
    private fun fails(block: () -> Unit) { try { block(); fail("Expected rejection") } catch (_: IllegalArgumentException) {} }
    private fun metadata(extra: String = "") = """{"available":true,"version":"0.2.2","size":${bytes.size},"sha256":"$sha","url":"/apk"$extra}"""

    @Test fun versionsCompareNumericallyAndRejectInvalidInput() {
        assertTrue(AppVersion.parse("0.10.0")!! > AppVersion.parse("0.2.9")!!)
        assertTrue(AppVersion.parse("1.0.0")!! > AppVersion.parse("0.99.99")!!)
        for (s in listOf("", "latest", "0.2", "0.2.3-beta", "-1.2.3", "9999999999.2.3")) assertNull(AppVersion.parse(s))
    }
    @Test fun checkToleratesExtraFieldsAndSendsNoCredentials() = runBlocking {
        response(metadata(",\"name\":\"agent-deck.apk\""))
        assertEquals(offer, client.check())
        val req = server.takeRequest()
        assertEquals("/web/apk-info", req.target); assertNull(req.headers["Authorization"])
    }
    @Test fun unavailableDoesNotPretendUpToDate() = runBlocking { response("""{"available":false,"version":null}"""); assertNull(client.check()) }
    @Test fun malformedMetadataRejected() = runBlocking { response(metadata().replace("/apk", "https://other.test/apk")); fails { runBlocking { client.check() } } }
    @Test fun oversizedMetadataRejected() = runBlocking { response(" ".repeat(70000)); fails { runBlocking { client.check() } } }
    @Test fun redirectIsNeverFollowed() = runBlocking {
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "http://other.test/apk").build())
        fails { runBlocking { client.check() } }; assertEquals(1, server.requestCount)
    }
    @Test fun downloadIsVerifiedAndReportsMeasuredProgress() = runBlocking {
        response(bytes.toString(Charsets.UTF_8)); val file = File.createTempFile("update", ".part")
        try {
            var count = 0L; client.download(offer, file) { n, total -> count = n; assertEquals(bytes.size.toLong(), total) }
            assertArrayEquals(bytes, file.readBytes()); assertEquals(bytes.size.toLong(), count)
            val req = server.takeRequest(); assertEquals("/apk", req.target); assertNull(req.headers["Authorization"])
        } finally { file.delete() }
    }
    @Test fun wrongChecksumDeletesPartial() = runBlocking {
        response(bytes.toString(Charsets.UTF_8)); val file = File.createTempFile("update", ".part")
        fails { runBlocking { client.download(offer.copy(sha256 = "0".repeat(64)), file) { _, _ -> } } }; assertFalse(file.exists())
    }
    @Test fun changedSizeDeletesPartial() = runBlocking {
        response("truncated"); val file = File.createTempFile("update", ".part")
        fails { runBlocking { client.download(offer, file) { _, _ -> } } }; assertFalse(file.exists())
    }
    @Test fun oversizedOrInvalidOffersAreRejectedBeforeDownload() {
        fails { offer.copy(size = ApkOffer.MAX_APK_BYTES + 1).validate() }
        fails { offer.copy(size = 0).validate() }; fails { offer.copy(sha256 = "wrong").validate() }
    }
    @Test fun downloadRedirectDeletesPartial() = runBlocking {
        server.enqueue(MockResponse.Builder().code(307).addHeader("Location", "https://other.test/apk").build())
        val file = File.createTempFile("update", ".part")
        fails { runBlocking { client.download(offer, file) { _, _ -> } } }; assertFalse(file.exists()); assertEquals(1, server.requestCount)
    }
}
