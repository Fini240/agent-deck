package de.finn.agentdeck.core.push

import java.security.GeneralSecurityException
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Decrypts Agent Deck FCM data messages (contract v1).
 *
 * Data map: `v = "1"`, `nonce` = base64 of 12 bytes, `ciphertext` = base64 of AES-256-GCM
 * ciphertext with the 16-byte tag appended. No associated data in v1.
 */
object PushCrypto {
    const val VERSION = "1"
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128
    private const val KEY_BYTES = 32

    fun decodeKey(base64Key: String): ByteArray {
        val key = decodeBase64(base64Key, "pushKey")
        if (key.size != KEY_BYTES) throw PushDecryptException(PushDecryptException.Reason.BAD_KEY, "pushKey must be 32 bytes, got ${key.size}")
        return key
    }

    fun decrypt(key: ByteArray, data: Map<String, String>): String {
        val version = data["v"]
        if (version != VERSION) {
            throw PushDecryptException(PushDecryptException.Reason.UNSUPPORTED_VERSION, "Unsupported push payload version: ${version ?: "missing"}")
        }
        val nonce = decodeBase64(data["nonce"] ?: missing("nonce"), "nonce")
        val ciphertext = decodeBase64(data["ciphertext"] ?: missing("ciphertext"), "ciphertext")
        return decrypt(key, nonce, ciphertext)
    }

    fun decrypt(key: ByteArray, nonce: ByteArray, ciphertextWithTag: ByteArray): String {
        if (key.size != KEY_BYTES) throw PushDecryptException(PushDecryptException.Reason.BAD_KEY, "pushKey must be 32 bytes")
        if (nonce.size != NONCE_BYTES) throw PushDecryptException(PushDecryptException.Reason.MALFORMED, "nonce must be 12 bytes")
        if (ciphertextWithTag.size < TAG_BITS / 8) throw PushDecryptException(PushDecryptException.Reason.MALFORMED, "ciphertext shorter than tag")
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            cipher.doFinal(ciphertextWithTag).toString(Charsets.UTF_8)
        } catch (e: GeneralSecurityException) {
            throw PushDecryptException(PushDecryptException.Reason.AUTHENTICATION_FAILED, "Push payload failed authentication", e)
        }
    }

    /** Test/tooling helper; the app never encrypts pushes itself. */
    fun encrypt(key: ByteArray, nonce: ByteArray, plaintext: String): Map<String, String> {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        val ct = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val enc = Base64.getEncoder()
        return mapOf("v" to VERSION, "nonce" to enc.encodeToString(nonce), "ciphertext" to enc.encodeToString(ct))
    }

    private fun missing(field: String): Nothing =
        throw PushDecryptException(PushDecryptException.Reason.MALFORMED, "Missing field: $field")

    private fun decodeBase64(value: String, field: String): ByteArray = try {
        Base64.getDecoder().decode(value.trim())
    } catch (e: IllegalArgumentException) {
        // Tolerate URL-safe encoding in case a sender uses it.
        try {
            Base64.getUrlDecoder().decode(value.trim())
        } catch (_: IllegalArgumentException) {
            throw PushDecryptException(PushDecryptException.Reason.MALFORMED, "Invalid base64 in $field", e)
        }
    }
}

class PushDecryptException(val reason: Reason, message: String, cause: Throwable? = null) : Exception(message, cause) {
    enum class Reason { UNSUPPORTED_VERSION, MALFORMED, BAD_KEY, AUTHENTICATION_FAILED, BAD_PAYLOAD }
}
