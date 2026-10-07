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
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
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


/** Everything the app knows about its pairing with one helper host. Secrets are only held in memory. */
data class Credentials(
    val server: ServerUrl,
    val serverName: String,
    val deviceId: String,
    val token: String,
    val pushKey: ByteArray,
) {
    /** Canonical host identity: the normalized HTTPS URL. */
    val hostKey: String get() = server.value

    /** Identity of this exact pairing (host + device). Changes when the same host is paired again. */
    val pairingKey: String get() = HostKeys.pairing(server.value, deviceId)

    override fun equals(other: Any?) = other is Credentials && other.deviceId == deviceId && other.server == server && other.token == token && other.pushKey.contentEquals(pushKey) && other.serverName == serverName
    override fun hashCode() = deviceId.hashCode()
    override fun toString() = "Credentials(server=$server, deviceId=$deviceId, token=<redacted>, pushKey=<redacted>)"
}

/** Short, stable, non-secret namespaces for per-host and per-pairing local state. */
object HostKeys {
    /** Namespace for one host (survives pairing the same host again). */
    fun host(url: String): String = "h" + digest(url)

    /** Namespace for one pairing of one host. */
    fun pairing(url: String, deviceId: String): String = "p" + digest("$url\u0000$deviceId")

    private fun digest(s: String) = java.security.MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
        .take(10).joinToString("") { "%02x".format(it) }
}

/**
 * One saved helper. [credentials] is null when its secrets can't be unlocked any more; such an
 * entry stays listed (with [error]) so it can be paired again or removed, and never affects others.
 */
data class SavedHost(
    val key: String,
    val label: String,
    val serverName: String,
    val deviceId: String,
    val addedAtMillis: Long,
    val credentials: Credentials?,
    val error: String? = null,
) {
    val server: ServerUrl get() = ServerUrl.trusted(key)
    val usable: Boolean get() = credentials != null
    val pairingKey: String get() = HostKeys.pairing(key, deviceId)
    override fun toString() = "SavedHost(key=$key, label=$label, deviceId=$deviceId, usable=$usable)"
}

class TooManyHosts : IllegalStateException("Up to ${CredentialStore.MAX_HOSTS} devices can be saved. Remove one first.")

/**
 * Persistent registry of up to [MAX_HOSTS] paired helpers (Mac, ZimaOS, …) with one active host.
 * URL, names and device IDs are stored plainly (not secret); each bearer token and per-device AES
 * push key is sealed with [SecretCipher]. The whole registry is one JSON value written with a single
 * synchronous commit, so a crash never leaves half an update. Existing callers keep using
 * [current]/[credentials], which always describe the active host.
 */
class CredentialStore(private val prefs: SharedPreferences, private val cipher: SecretCipher, private val clock: () -> Long = System::currentTimeMillis) {
    private data class Entry(val url: String, val label: String, val serverName: String, val deviceId: String, val token: String, val pushKey: String, val addedAt: Long)

    private var entries: List<Entry> = emptyList()
    private var activeKey: String? = null

    /** Set when some saved secrets can't be decrypted (e.g. Keystore key wiped). */
    var loadError: String? = null
        private set

    private val hostState = MutableStateFlow<List<SavedHost>>(emptyList())
    /** All saved hosts in the order they were added. */
    val hosts: StateFlow<List<SavedHost>> = hostState.asStateFlow()

    private val activeState = MutableStateFlow<Credentials?>(null)
    /** Active host's credentials (backwards compatible with the single-host store). */
    val credentials: StateFlow<Credentials?> = activeState.asStateFlow()

    private val activeKeyState = MutableStateFlow<String?>(null)
    val active: StateFlow<String?> = activeKeyState.asStateFlow()

    init {
        synchronized(this) {
            migrateLegacy()
            entries = readEntries()
            activeKey = prefs.getString(K_ACTIVE, null)?.takeIf { k -> entries.any { it.url == k } } ?: entries.firstOrNull()?.url
            publish()
        }
    }

    fun current(): Credentials? = activeState.value

    @Synchronized fun host(key: String): SavedHost? = hostState.value.firstOrNull { it.key == key }

    /** Credentials of a saved pairing, only while exactly that pairing (host + device) is still saved. */
    @Synchronized fun pairing(hostKey: String, deviceId: String): Credentials? =
        hostState.value.firstOrNull { it.key == hostKey && it.deviceId == deviceId }?.credentials

    /** True while [creds] is still a saved pairing (active or not) with the same token. */
    fun isSaved(creds: Credentials): Boolean = pairing(creds.hostKey, creds.deviceId) == creds

    /** Usable saved pairings, active first. Bounded by [MAX_HOSTS]. */
    fun usable(): List<Credentials> {
        val list = hostState.value.mapNotNull { it.credentials }
        val act = current()
        return if (act == null) list else listOf(act) + list.filter { it != act }
    }

    /**
     * Adds a host or replaces the pairing of the same canonical URL (keeping its name and position).
     * Other hosts are untouched. The saved host becomes active when [activate] is true.
     */
    @Synchronized
    fun save(server: ServerUrl, serverName: String, deviceId: String, token: String, pushKeyBase64: String, label: String? = null, activate: Boolean = true) {
        val pushKey = PushCrypto.decodeKey(pushKeyBase64) // validates 32 bytes before anything is stored
        val url = server.value
        val existing = entries.firstOrNull { it.url == url }
        if (existing == null && entries.size >= MAX_HOSTS) throw TooManyHosts()
        val entry = Entry(
            url = url,
            label = label?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_LABEL) ?: existing?.label ?: defaultLabel(server, serverName),
            serverName = serverName,
            deviceId = deviceId,
            token = seal(token.toByteArray(Charsets.UTF_8)),
            pushKey = seal(pushKey),
            addedAt = existing?.addedAt ?: clock(),
        )
        val next = if (existing == null) entries + entry else entries.map { if (it.url == url) entry else it }
        write(next, if (activate || activeKey == null) url else activeKey)
    }

    /** Makes a usable saved host active. Returns false for unknown or locked hosts. */
    @Synchronized
    fun select(key: String): Boolean {
        val h = hostState.value.firstOrNull { it.key == key } ?: return false
        if (!h.usable) return false
        if (activeKey == key) return true
        write(entries, key)
        return true
    }

    @Synchronized
    fun rename(key: String, label: String): Boolean {
        val clean = label.trim().take(MAX_LABEL)
        if (clean.isEmpty() || entries.none { it.url == key }) return false
        write(entries.map { if (it.url == key) it.copy(label = clean) else it }, activeKey)
        return true
    }

    /** Removes one host. If it was active, the next usable host becomes active. Returns the removed host. */
    @Synchronized
    fun remove(key: String): SavedHost? {
        val removed = hostState.value.firstOrNull { it.key == key } ?: return null
        val next = entries.filter { it.url != key }
        val nextActive = if (activeKey != key) activeKey else {
            hostState.value.firstOrNull { it.key != key && it.usable }?.key ?: next.firstOrNull()?.url
        }
        write(next, nextActive)
        return removed
    }

    /** Removes the active host only (single-host era API). */
    fun clear() {
        activeKey?.let { remove(it) }
    }

    /** Removes every saved host. */
    @Synchronized
    fun clearAll() = write(emptyList(), null)

    /** Host whose single-host data still needs moving into its namespace, if this store was just migrated. */
    fun pendingLegacyUiMigration(): String? = prefs.getString(K_LEGACY_UI, null)

    fun pendingLegacyUiDevice(): String? = prefs.getString(K_LEGACY_DEVICE, null)
    fun markLegacyUiMigrated() = prefs.edit(commit = true) { remove(K_LEGACY_UI); remove(K_LEGACY_DEVICE) }

    private fun write(next: List<Entry>, nextActive: String?) {
        val act = nextActive?.takeIf { k -> next.any { it.url == k } }
        prefs.edit(commit = true) {
            putString(K_HOSTS, encode(next))
            if (act == null) remove(K_ACTIVE) else putString(K_ACTIVE, act)
        }
        entries = next
        activeKey = act
        publish()
    }

    private fun publish() {
        var errors = 0
        val list = entries.map { e ->
            val (creds, err) = open(e)
            if (err != null) errors++
            SavedHost(e.url, e.label, e.serverName, e.deviceId, e.addedAt, creds, err)
        }
        loadError = when (errors) {
            0 -> null
            else -> list.filter { !it.usable }.joinToString(prefix = "Saved credentials for ", postfix = " can't be unlocked on this phone any more. Pair again.") { it.label }
        }
        var act = activeKey?.let { k -> list.firstOrNull { it.key == k } }
        if (act != null && !act.usable) {
            // A locked active host must not leave the app without a usable host when another exists.
            list.firstOrNull { it.usable }?.let { act = it; activeKey = it.key }
        }
        hostState.value = list
        activeKeyState.value = activeKey
        activeState.value = act?.credentials
    }

    private fun open(e: Entry): Pair<Credentials?, String?> = try {
        require(e.deviceId.isNotBlank())
        val token = String(unseal(e.token), Charsets.UTF_8)
        val key = unseal(e.pushKey)
        require(token.isNotBlank() && key.size == 32)
        Credentials(ServerUrl.trusted(e.url), e.serverName, e.deviceId, token, key) to null
    } catch (_: GeneralSecurityException) {
        null to "Saved credentials can't be unlocked on this phone any more. Pair again."
    } catch (_: IllegalArgumentException) {
        null to "Saved credentials are damaged. Pair again."
    }

    private fun readEntries(): List<Entry> {
        val raw = prefs.getString(K_HOSTS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val url = o.optString("url").takeIf { it.isNotEmpty() }?.let { ServerUrl.parse(it).getOrNull()?.value } ?: return@mapNotNull null
                Entry(url, o.optString("label").ifBlank { url }, o.optString("serverName"), o.optString("deviceId"),
                    o.optString("token"), o.optString("pushKey"), o.optLong("addedAt"))
            }.distinctBy { it.url }.take(MAX_HOSTS)
        } catch (_: JSONException) {
            emptyList()
        }
    }

    private fun encode(list: List<Entry>): String = JSONArray().apply {
        list.forEach { e ->
            put(JSONObject().put("url", e.url).put("label", e.label).put("serverName", e.serverName).put("deviceId", e.deviceId)
                .put("token", e.token).put("pushKey", e.pushKey).put("addedAt", e.addedAt))
        }
    }.toString()

    /**
     * Single-host stores (≤ 0.2.3) kept one pairing in flat keys. Moves it into the registry without
     * decrypting it (so even a locked pairing is kept), in the same commit that removes the old keys.
     */
    private fun migrateLegacy() {
        val server = prefs.getString(L_SERVER, null)
        val device = prefs.getString(L_DEVICE, null)
        val token = prefs.getString(L_TOKEN, null)
        val push = prefs.getString(L_PUSH_KEY, null)
        val hasLegacy = listOf(L_SERVER, L_DEVICE, L_TOKEN, L_PUSH_KEY, L_SERVER_NAME).any { prefs.contains(it) }
        if (!hasLegacy) return
        val url = server?.let { ServerUrl.parse(it).getOrNull() }
        val alreadyMigrated = prefs.contains(K_HOSTS)
        prefs.edit(commit = true) {
            if (!alreadyMigrated && url != null && device != null && token != null && push != null) {
                val name = prefs.getString(L_SERVER_NAME, null).orEmpty()
                putString(K_HOSTS, encode(listOf(Entry(url.value, defaultLabel(url, name), name, device, token, push, clock()))))
                putString(K_ACTIVE, url.value)
                putString(K_LEGACY_UI, url.value)
                putString(K_LEGACY_DEVICE, device)
            }
            listOf(L_SERVER, L_DEVICE, L_TOKEN, L_PUSH_KEY, L_SERVER_NAME).forEach { remove(it) }
        }
    }

    private fun seal(bytes: ByteArray) = Base64.encodeToString(cipher.encrypt(bytes), Base64.NO_WRAP)
    private fun unseal(value: String) = cipher.decrypt(Base64.decode(value, Base64.NO_WRAP))

    companion object {
        const val MAX_HOSTS = 8
        const val MAX_LABEL = 40
        private const val K_HOSTS = "hosts_v2"
        private const val K_ACTIVE = "active_v2"
        private const val K_LEGACY_UI = "legacy_ui_host"
        private const val K_LEGACY_DEVICE = "legacy_ui_device"
        private const val L_SERVER = "server"
        private const val L_SERVER_NAME = "server_name"
        private const val L_DEVICE = "device_id"
        private const val L_TOKEN = "token_sealed"
        private const val L_PUSH_KEY = "push_key_sealed"

        /** The helper's own name if it sent one, else the first part of its host name. */
        fun defaultLabel(server: ServerUrl, serverName: String): String =
            serverName.trim().ifEmpty { server.host.substringBefore('.') }.take(MAX_LABEL)

        fun create(context: Context, cipher: SecretCipher = KeystoreSecretCipher()) =
            CredentialStore(context.getSharedPreferences("credentials", Context.MODE_PRIVATE), cipher)
    }
}
