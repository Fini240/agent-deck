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
            "This APK was built without a Firebase config, so the Mac cannot reach the phone while the app is closed. " +
                "Live updates still work while the app is open.",
        )
        firebaseError != null -> Triple(Level.OFF, "Push failed to start", firebaseError)
        token is TokenState.Failed -> Triple(Level.WARNING, "No push token", "Firebase did not issue a token: ${token.message}")
        token != TokenState.Available -> Triple(Level.WARNING, "Waiting for push token", "Firebase has not issued a token yet.")
        registration is Registration.Failed -> Triple(Level.WARNING, "Push token not registered", "The Mac did not accept the token: ${registration.message}")
        registration !is Registration.Registered -> Triple(Level.WARNING, "Push token not registered", "The Mac does not have this phone's push token yet.")
        !permissionGranted -> Triple(
            Level.WARNING, "Notifications are blocked",
            "Push is registered, but Android will not show notifications until you allow them.",
        )
        else -> Triple(
            Level.OK, "Push registered with the Mac",
            "The Mac has this phone's token. Delivery is only proven once a real notification arrives.",
        )
    }
}

/**
 * Obtains the FCM token and registers it with the paired Mac. All Firebase calls are guarded:
 * in a push-unconfigured build none of them run.
 */
class PushRegistrar(
    private val context: Context,
    private val prefs: SharedPreferences,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _status = MutableStateFlow(PushStatus(buildConfigured = BuildConfig.PUSH_CONFIGURED))
    val status: StateFlow<PushStatus> = _status.asStateFlow()

    fun setPermissionGranted(granted: Boolean) = _status.update { it.copy(permissionGranted = granted) }

    /** Restores "registered" only if the stored registration matches this device and token. */
    fun restore(creds: Credentials?) {
        if (!BuildConfig.PUSH_CONFIGURED || creds == null) return
        val at = prefs.getLong(K_AT, 0L)
        if (at > 0 && prefs.getString(K_DEVICE, null) == creds.deviceId && prefs.getString(K_SERVER, null) == creds.server.value) {
            _status.update { it.copy(registration = PushStatus.Registration.Registered(at)) }
        }
    }

    /** Returns a token for the pair request if one is quickly available, else null. */
    suspend fun tokenForPairing(): String? = if (!BuildConfig.PUSH_CONFIGURED) null else withTimeoutOrNull(5_000) { fetchToken() }

    /** Ensures the Mac has the current token. Safe to call repeatedly. */
    suspend fun ensureRegistered(repository: AgentDeckRepository, creds: Credentials, force: Boolean = false) {
        if (!BuildConfig.PUSH_CONFIGURED) return
        val token = fetchToken() ?: return
        val hash = sha256(token)
        val same = prefs.getString(K_TOKEN_HASH, null) == hash && prefs.getString(K_DEVICE, null) == creds.deviceId &&
            prefs.getString(K_SERVER, null) == creds.server.value && prefs.getLong(K_AT, 0) > 0
        if (same && !force) {
            _status.update { it.copy(registration = PushStatus.Registration.Registered(prefs.getLong(K_AT, 0))) }
            return
        }
        _status.update { it.copy(registration = PushStatus.Registration.Registering) }
        try {
            repository.callFor(creds) { updateFcmToken(creds.deviceId, token) }
            markRegistered(creds, hash)
        } catch (e: ApiException) {
            _status.update { it.copy(registration = PushStatus.Registration.Failed(e.message ?: "unknown error")) }
            throw e
        }
    }

    /** Pairing sent the token in the pair request itself. */
    fun markRegisteredViaPairing(creds: Credentials, token: String) = markRegistered(creds, sha256(token))

    fun forget() {
        prefs.edit(commit = true) { clear() }
        _status.update { it.copy(registration = PushStatus.Registration.NotRegistered) }
    }

    /** Token rotated: registration no longer valid until re-sent. */
    fun onTokenRotated() {
        prefs.edit { remove(K_AT); remove(K_TOKEN_HASH) }
        _status.update { it.copy(token = PushStatus.TokenState.Available, registration = PushStatus.Registration.NotRegistered) }
    }

    private fun markRegistered(creds: Credentials, hash: String) {
        val now = clock()
        prefs.edit(commit = true) {
            putString(K_TOKEN_HASH, hash)
            putString(K_DEVICE, creds.deviceId)
            putString(K_SERVER, creds.server.value)
            putLong(K_AT, now)
        }
        _status.update { it.copy(registration = PushStatus.Registration.Registered(now)) }
    }

    private suspend fun fetchToken(): String? {
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
        } catch (e: Exception) {
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
