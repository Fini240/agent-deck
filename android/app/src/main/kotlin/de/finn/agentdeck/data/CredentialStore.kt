package de.finn.agentdeck.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import de.finn.agentdeck.core.api.ServerUrl
import de.finn.agentdeck.core.push.PushCrypto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts small secrets at rest. Production uses a non-exportable Android Keystore key. */
interface SecretCipher {
    fun encrypt(plain: ByteArray): ByteArray
    fun decrypt(sealed: ByteArray): ByteArray
}

/**
 * AES-256-GCM with a non-exportable AndroidKeyStore key. Hardware backing depends on the
 * device; stored blobs are `iv(12) || ciphertext+tag`. No user authentication is required,
 * so the FCM service can decrypt pushes while the phone is locked in a pocket.
 */
class KeystoreSecretCipher(private val alias: String = "agentdeck.credentials.v1") : SecretCipher {
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return gen.generateKey()
    }

    override fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return cipher.iv + cipher.doFinal(plain)
    }

    override fun decrypt(sealed: ByteArray): ByteArray {
        require(sealed.size > 12 + 16) { "sealed blob too short" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed, 0, 12))
        return cipher.doFinal(sealed, 12, sealed.size - 12)
    }
}

/** Everything the app knows about its pairing with one Mac. Secrets are only held in memory. */
data class Credentials(
    val server: ServerUrl,
    val serverName: String,
    val deviceId: String,
    val token: String,
    val pushKey: ByteArray,
) {
    override fun equals(other: Any?) = other is Credentials && other.deviceId == deviceId && other.server == server && other.token == token
    override fun hashCode() = deviceId.hashCode()
    override fun toString() = "Credentials(server=$server, deviceId=$deviceId, token=<redacted>, pushKey=<redacted>)"
}

/**
 * Persists pairing state. Server URL, name and device ID are stored plainly (not secret); the
 * bearer token and the per-device AES push key are sealed with [SecretCipher].
 */
class CredentialStore(private val prefs: SharedPreferences, private val cipher: SecretCipher) {
    /** Set when stored secrets exist but can't be decrypted (e.g. Keystore key wiped). Declared before [state] so load() can set it. */
    var loadError: String? = null
        private set

    private val state = MutableStateFlow(load())
    val credentials: StateFlow<Credentials?> = state.asStateFlow()

    fun current(): Credentials? = state.value

    fun save(server: ServerUrl, serverName: String, deviceId: String, token: String, pushKeyBase64: String) {
        val pushKey = PushCrypto.decodeKey(pushKeyBase64) // validates 32 bytes before anything is stored
        prefs.edit(commit = true) {
            putString(K_SERVER, server.value)
            putString(K_SERVER_NAME, serverName)
            putString(K_DEVICE, deviceId)
            putString(K_TOKEN, seal(token.toByteArray(Charsets.UTF_8)))
            putString(K_PUSH_KEY, seal(pushKey))
        }
        loadError = null
        state.value = Credentials(server, serverName, deviceId, token, pushKey)
    }

    fun clear() {
        prefs.edit(commit = true) { clear() }
        state.value = null
    }

    private fun load(): Credentials? {
        val server = prefs.getString(K_SERVER, null) ?: return null
        val device = prefs.getString(K_DEVICE, null) ?: return null
        val token = prefs.getString(K_TOKEN, null) ?: return null
        val pushKey = prefs.getString(K_PUSH_KEY, null) ?: return null
        return try {
            Credentials(
                server = ServerUrl.trusted(server),
                serverName = prefs.getString(K_SERVER_NAME, null).orEmpty(),
                deviceId = device,
                token = String(open(token), Charsets.UTF_8),
                pushKey = open(pushKey),
            )
        } catch (e: GeneralSecurityException) {
            loadError = "Saved credentials can't be unlocked on this phone any more. Pair again."
            null
        } catch (e: IllegalArgumentException) {
            loadError = "Saved credentials are damaged. Pair again."
            null
        }
    }

    private fun seal(bytes: ByteArray) = Base64.encodeToString(cipher.encrypt(bytes), Base64.NO_WRAP)
    private fun open(value: String) = cipher.decrypt(Base64.decode(value, Base64.NO_WRAP))

    companion object {
        private const val K_SERVER = "server"
        private const val K_SERVER_NAME = "server_name"
        private const val K_DEVICE = "device_id"
        private const val K_TOKEN = "token_sealed"
        private const val K_PUSH_KEY = "push_key_sealed"

        fun create(context: Context, cipher: SecretCipher = KeystoreSecretCipher()) =
            CredentialStore(context.getSharedPreferences("credentials", Context.MODE_PRIVATE), cipher)
    }
}
