package de.finn.agentdeck.push

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import de.finn.agentdeck.BuildConfig
import de.finn.agentdeck.core.api.ApiException
import de.finn.agentdeck.data.AgentDeckRepository
import de.finn.agentdeck.data.Credentials
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.security.MessageDigest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Every fact needed to describe push honestly. "Connected" requires all of them. */
data class PushStatus(
    val buildConfigured: Boolean,
    val firebaseError: String? = null,
    val token: TokenState = TokenState.Unknown,
    val registration: Registration = Registration.NotRegistered,
    val permissionGranted: Boolean = true,
) {
    sealed interface TokenState {
        data object Unknown : TokenState
        data object Fetching : TokenState
        data object Available : TokenState
        data class Failed(val message: String) : TokenState
    }

    sealed interface Registration {
        data object NotRegistered : Registration
        data object Registering : Registration
        data class Registered(val atMillis: Long) : Registration
        data class Failed(val message: String) : Registration
    }

    enum class Level { OK, WARNING, OFF }

    val connected: Boolean
        get() = buildConfigured && firebaseError == null && token == TokenState.Available &&
            registration is Registration.Registered && permissionGranted

    /** Headline + explanation for Settings. Never claims delivery that hasn't been set up. */
    fun describe(): Triple<Level, String, String> = when {
        !buildConfigured -> Triple(
            Level.OFF, "Push not configured in this build",
            "This APK was built without a Firebase config, so the selected device cannot reach the phone while the app is closed. " +
                "Live updates still work while the app is open.",
        )
        firebaseError != null -> Triple(Level.OFF, "Push failed to start", firebaseError)
        token is TokenState.Failed -> Triple(Level.WARNING, "No push token", "Firebase did not issue a token: ${token.message}")
        token != TokenState.Available -> Triple(Level.WARNING, "Waiting for push token", "Firebase has not issued a token yet.")
        registration is Registration.Failed -> Triple(Level.WARNING, "Push token not registered", "The selected device did not accept the token: ${registration.message}")
        registration !is Registration.Registered -> Triple(Level.WARNING, "Push token not registered", "The selected device does not have this phone's push token yet.")
        !permissionGranted -> Triple(
            Level.WARNING, "Notifications are blocked",
            "Push is registered, but Android will not show notifications until you allow them.",
        )
        else -> Triple(
            Level.OK, "Push registered with the selected device",
            "The selected device has this phone's token. Delivery is only proven once a real notification arrives.",
        )
    }
}

/**
 * Obtains the FCM token and registers it with each paired device. All Firebase calls are guarded:
 * in a push-unconfigured build none of them run.
 */
class PushRegistrar(
    private val context: Context,
    private val prefs: SharedPreferences,
    private val clock: () -> Long = System::currentTimeMillis,
    private val configured: Boolean = BuildConfig.PUSH_CONFIGURED,
    private val tokenProvider: (suspend () -> String?)? = null,
) {
    private val _status = MutableStateFlow(PushStatus(buildConfigured = configured))
    val status: StateFlow<PushStatus> = _status.asStateFlow()

    @Volatile private var selected: Credentials? = null
    private val registrations = mutableMapOf<String, PushStatus.Registration>()
    private fun key(creds: Credentials, field: String) = "${creds.pairingKey}:$field"
    private fun registration(creds: Credentials, value: PushStatus.Registration) {
        synchronized(registrations) { registrations[creds.pairingKey] = value }
        _status.update { if (selected == creds) it.copy(registration = value) else it }
    }

    /** Register independently, so an offline machine cannot block the others. */
    suspend fun ensureRegisteredAll(repository: AgentDeckRepository, credentials: List<Credentials>): List<ApiException> = coroutineScope {
        credentials.map { creds -> async {
            try { ensureRegistered(repository, creds); null }
            catch (e: ApiException) { e }
        } }.awaitAll().filterNotNull()
    }

    fun setPermissionGranted(granted: Boolean) = _status.update { it.copy(permissionGranted = granted) }

    /** Restores "registered" only if the stored registration matches this device and token. */
    fun restore(creds: Credentials?) {
        selected = creds
        val saved = creds?.let {
            val at = prefs.getLong(key(it, K_AT), 0)
            synchronized(registrations) { registrations[it.pairingKey] } ?: if (at > 0) PushStatus.Registration.Registered(at) else null
        } ?: PushStatus.Registration.NotRegistered
        _status.update { it.copy(registration = saved) }
    }

    /** Returns a token for the pair request if one is quickly available, else null. */
    suspend fun tokenForPairing(): String? = if (!configured) null else withTimeoutOrNull(5_000) { fetchToken() }

    /** Ensures the selected device has the current token. Safe to call repeatedly. */
    suspend fun ensureRegistered(repository: AgentDeckRepository, creds: Credentials, force: Boolean = false) {
        if (!configured) return
        val token = fetchToken() ?: return
        val hash = sha256(token)
        val same = prefs.getString(key(creds, K_TOKEN_HASH), null) == hash && prefs.getString(key(creds, K_DEVICE), null) == creds.deviceId &&
            prefs.getString(key(creds, K_SERVER), null) == creds.server.value && prefs.getLong(key(creds, K_AT), 0) > 0
        if (same && !force) {
            registration(creds, PushStatus.Registration.Registered(prefs.getLong(key(creds, K_AT), 0)))
            return
        }
        registration(creds, PushStatus.Registration.Registering)
        try {
            repository.callSaved(creds) { updateFcmToken(creds.deviceId, token) }
            markRegistered(creds, hash)
        } catch (e: ApiException) {
            registration(creds, PushStatus.Registration.Failed(e.message ?: "unknown error"))
            throw e
        }
    }

    /** Pairing sent the token in the pair request itself. */
    fun markRegisteredViaPairing(creds: Credentials, token: String) = markRegistered(creds, sha256(token))

    fun forget(pairingKey: String? = selected?.pairingKey) {
        if (pairingKey != null) {
            prefs.edit(commit = true) { prefs.all.keys.filter { it.startsWith("$pairingKey:") }.forEach { remove(it) } }
            synchronized(registrations) { registrations.remove(pairingKey) }
        }
        if (pairingKey == selected?.pairingKey) _status.update { it.copy(registration = PushStatus.Registration.NotRegistered) }
    }

    /** Token rotated: registration no longer valid until re-sent. */
    fun onTokenRotated() {
        prefs.edit(commit = true) { prefs.all.keys.filter { it.endsWith(":$K_AT") || it.endsWith(":$K_TOKEN_HASH") || it == K_AT || it == K_TOKEN_HASH }.forEach { remove(it) } }
        synchronized(registrations) { registrations.clear() }
        _status.update { it.copy(token = PushStatus.TokenState.Available, registration = PushStatus.Registration.NotRegistered) }
    }

    private fun markRegistered(creds: Credentials, hash: String) {
        val now = clock()
        prefs.edit(commit = true) {
            putString(key(creds, K_TOKEN_HASH), hash)
            putString(key(creds, K_DEVICE), creds.deviceId)
            putString(key(creds, K_SERVER), creds.server.value)
            putLong(key(creds, K_AT), now)
        }
        registration(creds, PushStatus.Registration.Registered(now))
    }

    private suspend fun fetchToken(): String? {
        tokenProvider?.let { provider ->
            val token = provider()
            _status.update { it.copy(token = if (token == null) PushStatus.TokenState.Failed("No token") else PushStatus.TokenState.Available) }
            return token
        }
        val messaging = try {
            if (FirebaseApp.getApps(context).isEmpty()) FirebaseApp.initializeApp(context)
            FirebaseMessaging.getInstance()
        } catch (e: IllegalStateException) {
            _status.update { it.copy(firebaseError = "Firebase could not initialise: ${e.message}") }
            return null
        }
        _status.update { it.copy(firebaseError = null, token = PushStatus.TokenState.Fetching) }
        return try {
            val token = messaging.token.await()
            _status.update { it.copy(token = PushStatus.TokenState.Available) }
            token
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            _status.update { it.copy(token = PushStatus.TokenState.Failed(e.message ?: e.javaClass.simpleName)) }
            null
        }
    }

    private fun sha256(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        private const val K_TOKEN_HASH = "token_sha256"
        private const val K_DEVICE = "device_id"
        private const val K_SERVER = "server"
        private const val K_AT = "registered_at"

        fun create(context: Context) = PushRegistrar(context.applicationContext, context.getSharedPreferences("push", Context.MODE_PRIVATE))
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnCompleteListener { task ->
        val e = task.exception
        when {
            e != null -> cont.resumeWithException(e)
            task.isCanceled -> cont.cancel()
            else -> cont.resume(task.result)
        }
    }
}
