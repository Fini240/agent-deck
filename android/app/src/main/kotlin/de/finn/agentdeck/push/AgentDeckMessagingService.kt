package de.finn.agentdeck.push

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import de.finn.agentdeck.AgentDeckApplication
import de.finn.agentdeck.AppGraph
import de.finn.agentdeck.core.model.Agents
import de.finn.agentdeck.core.push.NotificationPlanner
import de.finn.agentdeck.core.push.PushCrypto
import de.finn.agentdeck.core.push.PushDecryptException
import de.finn.agentdeck.core.push.PushPayload
import de.finn.agentdeck.data.CredentialStore
import de.finn.agentdeck.data.Credentials
import de.finn.agentdeck.notify.NotificationTarget
import de.finn.agentdeck.notify.Notifier
import java.util.concurrent.TimeUnit

/**
 * Receives encrypted FCM data messages. Plaintext only exists in memory after AES-GCM
 * verification with this device's push key; nothing from the payload is logged.
 */
class AgentDeckMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        val graph = (application as AgentDeckApplication).graph
        handle(graph, message.data, Notifier(this))
    }

    override fun onNewToken(token: String) {
        val graph = (application as AgentDeckApplication).graph
        graph.push.onTokenRotated()
        FcmTokenWorker.enqueue(this)
    }

    companion object {
        private const val TAG = "AgentDeckPush"

        /**
         * Shared with tests: find the saved pairing whose key authenticates the push, gate and show.
         * Works for every saved host, active or not. Returns the payload that was shown, if any.
         */
        fun handle(graph: AppGraph, data: Map<String, String>, notifier: Notifier, nowMillis: Long = System.currentTimeMillis()): PushPayload? {
            val (creds, payload) = route(graph.credentials.usable(), data) ?: return null
            val host = graph.credentials.host(creds.hostKey)?.takeIf { it.deviceId == creds.deviceId } ?: return null
            val target = NotificationTarget(creds.hostKey, creds.deviceId, host.label)
            if (!graph.notificationGate.shouldShow(payload, nowMillis, target.pairingKey)) return null
            val plan = NotificationPlanner.plan(payload) ?: return null
            notifier.show(plan, Agents.displayName(payload.agent), target)
            return payload
        }

        /**
         * Each pairing has its own random AES-256-GCM push key, so a successful authenticated
         * decryption proves which saved pairing sent the push; no routing metadata is needed. At most
         * [CredentialStore.MAX_HOSTS] keys are tried. Nothing from the payload is logged.
         */
        fun route(candidates: List<Credentials>, data: Map<String, String>): Pair<Credentials, PushPayload>? {
            for (creds in candidates.take(CredentialStore.MAX_HOSTS)) {
                val plain = try {
                    PushCrypto.decrypt(creds.pushKey, data)
                } catch (e: PushDecryptException) {
                    if (e.reason == PushDecryptException.Reason.AUTHENTICATION_FAILED) continue
                    Log.w(TAG, "Dropped push: ${e.reason}") // malformed for every key alike
                    return null
                }
                return try {
                    creds to PushPayload.parse(plain)
                } catch (e: PushDecryptException) {
                    Log.w(TAG, "Dropped push: ${e.reason}")
                    null
                }
            }
            if (candidates.isNotEmpty()) Log.w(TAG, "Dropped push: no saved pairing matches")
            return null
        }
    }
}

/** Sends a rotated FCM token to the Mac once the network is available. */
class FcmTokenWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = (applicationContext as AgentDeckApplication).graph
        // Every saved host gets the new token; one offline host doesn't stop the others. Hosts that
        // already have this token are skipped on retries.
        val failures = graph.push.ensureRegisteredAll(graph.repository, graph.credentials.usable())
        return when {
            failures.isEmpty() -> Result.success()
            failures.any { it.isTransient } && runAttemptCount < 8 -> Result.retry()
            else -> Result.failure()
        }
    }

    companion object {
        fun enqueue(context: Context) {
            val req = OneTimeWorkRequestBuilder<FcmTokenWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("fcm-token", ExistingWorkPolicy.REPLACE, req)
        }
    }
}
