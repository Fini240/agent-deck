package de.finn.agentdeck

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import de.finn.agentdeck.core.api.ApiException
import de.finn.agentdeck.core.push.GateStore
import de.finn.agentdeck.core.push.NotificationGate
import de.finn.agentdeck.data.AgentDeckRepository
import de.finn.agentdeck.data.CredentialStore
import de.finn.agentdeck.data.DraftStore
import de.finn.agentdeck.notify.Notifier
import de.finn.agentdeck.push.PushRegistrar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Manual dependency graph; small enough that a DI framework would only add weight. */
class AppGraph(
    val context: Context,
    val credentials: CredentialStore,
    val drafts: DraftStore,
    val push: PushRegistrar,
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) {
    val repository = AgentDeckRepository(credentials.credentials, scope)
    val notificationGate = NotificationGate(store = PrefsGateStore(context.getSharedPreferences("notification_gate", Context.MODE_PRIVATE)))
    val notifier = Notifier(context)

    @Volatile var foreground: Boolean = false
        private set

    fun onForeground() {
        foreground = true
        push.setPermissionGranted(notifier.canPost())
        repository.startLive()
        registerPushIfPaired()
    }

    fun onBackground() {
        foreground = false
        repository.stopLive()
    }

    fun registerPushIfPaired() {
        val creds = credentials.current() ?: return
        scope.launch {
            try {
                push.ensureRegistered(repository, creds)
            } catch (_: ApiException) {
                // Status flow carries the error for Settings.
            }
        }
    }

    fun onPaired() {
        // Notifications of a previous pairing would offer replies through the wrong device.
        notifier.cancelAll()
        repository.onCredentialsChanged(foreground)
        registerPushIfPaired()
    }

    fun forgetPairing() {
        credentials.clear()
        push.forget()
        notifier.cancelAll()
        repository.onCredentialsChanged(foreground)
    }
}

/** Persists the notification gate so ordering survives the fresh process FCM often starts. */
class PrefsGateStore(private val prefs: SharedPreferences) : GateStore {
    override fun load(): String? = prefs.getString(KEY, null)
    override fun save(value: String) = prefs.edit(commit = true) { putString(KEY, value) }

    private companion object { const val KEY = "state" }
}

class AgentDeckApplication : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        val credentials = CredentialStore.create(this)
        graph = AppGraph(this, credentials, DraftStore.create(this), PushRegistrar.create(this))
        graph.notifier.ensureChannels()
        graph.push.restore(credentials.current())
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = graph.onForeground()
            override fun onStop(owner: LifecycleOwner) = graph.onBackground()
        })
    }
}
