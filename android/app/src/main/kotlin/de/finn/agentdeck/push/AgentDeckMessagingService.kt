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
import de.finn.agentdeck.core.api.ApiException
import de.finn.agentdeck.core.model.Agents
import de.finn.agentdeck.core.push.NotificationPlanner
import de.finn.agentdeck.core.push.PushDecryptException
import de.finn.agentdeck.core.push.PushPayload
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

        /** Shared with tests: decrypt, gate and show. Returns the payload that was shown, if any. */
        fun handle(graph: AppGraph, data: Map<String, String>, notifier: Notifier): PushPayload? {
            val creds = graph.credentials.current() ?: return null
            val payload = try {
                PushPayload.open(creds.pushKey, data)
            } catch (e: PushDecryptException) {
                Log.w(TAG, "Dropped push: ${e.reason}") // reason only, never content
                return null
            }
            if (!graph.notificationGate.shouldShow(payload, System.currentTimeMillis())) return null
            val plan = NotificationPlanner.plan(payload) ?: return null
            notifier.show(plan, Agents.displayName(payload.agent), creds.deviceId)
            return payload
        }
    }
}

/** Sends a rotated FCM token to the Mac once the network is available. */
class FcmTokenWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = (applicationContext as AgentDeckApplication).graph
        val creds = graph.credentials.current() ?: return Result.success()
        return try {
            graph.push.ensureRegistered(graph.repository, creds, force = true)
            Result.success()
        } catch (e: ApiException) {
            if (e.isTransient && runAttemptCount < 8) Result.retry() else Result.failure()
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
