package de.finn.agentdeck.core.push

import de.finn.agentdeck.core.model.AgentDeckJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class PushCryptoTest {
    private val fixture: JsonObject =
        AgentDeckJson.parseToJsonElement(javaClass.getResource("/push-fixture.json")!!.readText()).jsonObject
    private val key = PushCrypto.decodeKey(fixture["keyBase64"]!!.jsonPrimitive.content)

    private fun JsonObject.dataMap(): Map<String, String> = mapValues { it.value.jsonPrimitive.content }

    @Test
    fun decryptsEveryPythonGeneratedFixtureCase() {
        val cases = fixture["cases"]!!.jsonArray
        assertEquals(3, cases.size)
        for (case in cases) {
            val obj = case.jsonObject
            val data = obj["data"]!!.jsonObject.dataMap()
            val plaintext = PushCrypto.decrypt(key, data)
            assertEquals(obj["name"]!!.jsonPrimitive.content, obj["plaintext"]!!.jsonPrimitive.content, plaintext)
        }
    }

    @Test
    fun parsesDecryptedPayloadsPerContract() {
        val cases = fixture["cases"]!!.jsonArray.map { it.jsonObject }
        val determinate = PushPayload.open(key, cases[0]["data"]!!.jsonObject.dataMap())
        assertEquals(PushKind.PROGRESS, determinate.kind)
        assertEquals(3.0, determinate.progress!!.current, 0.0)
        assertEquals(10.0, determinate.progress!!.total, 0.0)
        assertEquals("tests", determinate.progress!!.unit)

        val unknown = PushPayload.open(key, cases[1]["data"]!!.jsonObject.dataMap())
        assertNull(unknown.progress)

        val completed = PushPayload.open(key, cases[2]["data"]!!.jsonObject.dataMap())
        assertEquals(PushKind.COMPLETED, completed.kind)
        assertEquals("Alle Tests grün – 10/10", completed.body)
    }

    @Test
    fun rejectsTamperedCiphertext() {
        val tampered = fixture["tampered"]!!.jsonObject.dataMap()
        val e = assertThrows(PushDecryptException::class.java) { PushCrypto.decrypt(key, tampered) }
        assertEquals(PushDecryptException.Reason.AUTHENTICATION_FAILED, e.reason)
    }

    @Test
    fun rejectsWrongKey() {
        val data = fixture["cases"]!!.jsonArray[0].jsonObject["data"]!!.jsonObject.dataMap()
        val wrong = ByteArray(32) { 7 }
        val e = assertThrows(PushDecryptException::class.java) { PushCrypto.decrypt(wrong, data) }
        assertEquals(PushDecryptException.Reason.AUTHENTICATION_FAILED, e.reason)
    }

    @Test
    fun rejectsUnknownVersionAndMalformedFields() {
        val data = fixture["cases"]!!.jsonArray[0].jsonObject["data"]!!.jsonObject.dataMap()
        assertEquals(
            PushDecryptException.Reason.UNSUPPORTED_VERSION,
            assertThrows(PushDecryptException::class.java) { PushCrypto.decrypt(key, data + ("v" to "2")) }.reason,
        )
        assertEquals(
            PushDecryptException.Reason.MALFORMED,
            assertThrows(PushDecryptException::class.java) { PushCrypto.decrypt(key, data - "nonce") }.reason,
        )
        assertEquals(
            PushDecryptException.Reason.MALFORMED,
            assertThrows(PushDecryptException::class.java) { PushCrypto.decrypt(key, data + ("nonce" to "AAAA")) }.reason,
        )
        assertEquals(
            PushDecryptException.Reason.BAD_KEY,
            assertThrows(PushDecryptException::class.java) { PushCrypto.decodeKey("AAAA") }.reason,
        )
    }

    @Test
    fun roundTripsThroughOwnEncryptHelper() {
        val nonce = ByteArray(12) { it.toByte() }
        val data = PushCrypto.encrypt(key, nonce, """{"eventId":"e","type":"input","sessionId":"s"}""")
        assertEquals(PushKind.INPUT, PushPayload.open(key, data).kind)
    }

    /**
     * Cross-checks a fixture written by the backend agent, when present in the shared repo.
     * Skipped (not failed) if the backend has not produced one yet.
     */
    @Test
    fun decryptsBackendFixtureWhenPresent() {
        val candidates = listOf(
            "../../../server/tests/fixtures/push_fixture.json",
            "../../../server/tests/fixtures/push-fixture.json",
            "../../../coordination/push-fixture.json",
        ).map { File(System.getProperty("user.dir"), it).canonicalFile }
        val file = candidates.firstOrNull { it.isFile }
        assumeTrue("No backend push fixture found yet", file != null)
        val root = AgentDeckJson.parseToJsonElement(file!!.readText()).jsonObject
        val backendKey = PushCrypto.decodeKey(
            (root["keyBase64"] ?: root["pushKey"] ?: root["key"])!!.jsonPrimitive.content,
        )
        val cases = root["cases"]?.jsonArray?.map { it.jsonObject } ?: listOf(root)
        assertTrue(cases.isNotEmpty())
        for (case in cases) {
            val data = (case["data"] ?: case["encrypted"])!!.jsonObject.dataMap()
            val payload = PushPayload.open(backendKey, data)
            assertTrue(payload.eventId.isNotBlank())
        }
    }
}
