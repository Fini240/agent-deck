package de.finn.agentdeck.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import de.finn.agentdeck.AgentDeckApplication
import de.finn.agentdeck.core.api.ApiException
import de.finn.agentdeck.data.Credentials
import de.finn.agentdeck.data.DraftStore
import de.finn.agentdeck.data.HostKeys
import de.finn.agentdeck.data.SavedHost
import kotlinx.coroutines.CancellationException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Receives a direct reply typed into a notification and hands it to [ReplyWorker]. */
class ReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_REPLY) return
        val sessionId = intent.getStringExtra(EXTRA_SESSION) ?: return
        val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_TEXT)?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        val store = (context.applicationContext as AgentDeckApplication).graph.credentials
        val notifier = Notifier(context)
        val origin = origin(store.hosts.value, intent.getStringExtra(EXTRA_HOST), intent.getStringExtra(EXTRA_DEVICE))
        if (origin == null) {
            // Can't tell which device this came from: never guess (and never use the active one).
            notifier.showOrphanReplyFailure("orphan|$sessionId", text)
            return
        }
        // Update right away so the shade stops showing the reply spinner.
        notifier.showReplyStatus(origin, sessionId, "Sending reply…", text, failed = false)
        enqueue(context, origin.hostKey, origin.deviceId, sessionId, text)
    }

    companion object {
        const val ACTION_REPLY = "de.finn.agentdeck.action.REPLY"
        const val EXTRA_SESSION = "session_id"
        const val EXTRA_HOST = "host"
        const val EXTRA_DEVICE = "device_id"
        const val KEY_TEXT = "reply_text"

        /**
         * The saved device a reply belongs to. Current notifications carry host + device. Ones posted
         * by app versions before multi-host carry only the device ID: accepted only when exactly one
         * saved host has that device ID. Anything else fails closed (null).
         */
        fun origin(hosts: List<SavedHost>, hostKey: String?, deviceId: String?): NotificationTarget? {
            if (deviceId.isNullOrEmpty()) return null
            val match = if (hostKey != null) {
                hosts.filter { it.key == hostKey && it.deviceId == deviceId }
            } else {
                hosts.filter { it.deviceId == deviceId }
            }
            val h = match.singleOrNull() ?: return null
            return NotificationTarget(h.key, h.deviceId, h.label)
        }

        /**
         * One unique work per (host, pairing, session, text): a duplicate broadcast while the first is
         * pending is dropped, and retries reuse the same requestId so the helper applies the reply at
         * most once.
         */
        fun enqueue(context: Context, hostKey: String, deviceId: String, sessionId: String, text: String, nowMillis: Long = System.currentTimeMillis()) {
            val request = OneTimeWorkRequestBuilder<ReplyWorker>()
                .setInputData(
                    workDataOf(
                        ReplyWorker.K_SESSION to sessionId,
                        ReplyWorker.K_HOST to hostKey,
                        ReplyWorker.K_DEVICE to deviceId,
                        ReplyWorker.K_TEXT to text,
                        ReplyWorker.K_REQUEST to UUID.randomUUID().toString(),
                        ReplyWorker.K_CREATED to nowMillis,
                    ),
                )
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .addTag(ReplyWorker.TAG)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(uniqueName(hostKey, deviceId, sessionId, text), ExistingWorkPolicy.KEEP, request)
        }

        fun uniqueName(hostKey: String, deviceId: String, sessionId: String, text: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest("$hostKey\u0000$deviceId\u0000$sessionId\u0000$text".toByteArray())
            return "reply-" + digest.take(12).joinToString("") { "%02x".format(it) }
        }
    }
}

/** A notification reply as queued by [ReplyReceiver], bound to the saved pairing it came from. */
data class QueuedReply(val hostKey: String, val deviceId: String, val sessionId: String, val text: String, val requestId: String, val createdAtMillis: Long) {
    val draftKey: String get() = DraftStore.key(hostKey, sessionId, pairingKey)
    val pairingKey: String get() = HostKeys.pairing(hostKey, deviceId)
}

/**
 * Decides what happens to one attempt of a notification reply. Kept apart from WorkManager so the
 * failure paths are testable: every outcome either sends once through the reply's own pairing,
 * retries with the same requestId, or ends with the text saved as that host's draft and a visible
 * "not sent" notification. It never falls back to another (e.g. the active) host.
 */
class ReplyDelivery(
    /** The saved credentials of exactly this host + device, or null when removed/paired again. */
    private val resolve: (hostKey: String, deviceId: String) -> Credentials?,
    private val send: suspend (identity: Credentials, sessionId: String, text: String, requestId: String) -> Unit,
    private val drafts: DraftStore,
    private val status: (reply: QueuedReply, title: String, text: String, failed: Boolean) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    enum class Outcome { SENT, RETRY, FAILED }

    suspend fun deliver(r: QueuedReply, attempt: Int): Outcome {
        val identity = resolve(r.hostKey, r.deviceId)
            // Removed or paired again since the reply was typed: never send it through another
            // pairing or host. The requestId is meaningless there, so drop it.
            ?: return fail(r, "This device was removed or paired again before the reply was sent.", requestId = null)
        if (clock() - r.createdAtMillis > MAX_AGE_MILLIS) {
            // Sends always interrupt; a reply stuck offline this long would cut into newer work.
            return fail(r, "It could not be sent within ${MAX_AGE_MILLIS / 60_000} minutes.", r.requestId)
        }
        return try {
            send(identity, r.sessionId, r.text, r.requestId)
            status(r, "Reply sent", r.text, false)
            Outcome.SENT
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            if (e.isTransient && attempt < MAX_ATTEMPTS) {
                status(r, "Reply waiting to send", "Will retry: ${e.message}", false)
                Outcome.RETRY
            } else {
                if (e is ApiException.Http && e.code == "delivery_uncertain") {
                    drafts.restoreFailedReply(r.draftKey, r.text, r.requestId, r.pairingKey)
                    status(r, "Delivery not confirmed", "Your reply may already be in the chat. Check it before sending another message. Your text is saved as a draft.", true)
                    Outcome.FAILED
                } else fail(r, e.message.orEmpty(), r.requestId.takeUnless { e is ApiException.NotPaired })
            }
        } catch (e: Exception) {
            fail(r, "Unexpected error (${e.javaClass.simpleName}).", r.requestId)
        }
    }

    private fun fail(r: QueuedReply, reason: String, requestId: String?): Outcome {
        drafts.restoreFailedReply(r.draftKey, r.text, requestId, r.pairingKey)
        status(r, "Reply not sent", "$reason Your text is saved as a draft in the app.", true)
        return Outcome.FAILED
    }

    companion object {
        const val MAX_ATTEMPTS = 5
        const val MAX_AGE_MILLIS = 30 * 60_000L
    }
}

/** Sends a notification reply to the same session on its own host with `interrupt = true`. */
class ReplyWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val reply = QueuedReply(
            // Work queued by app versions before multi-host has no host: it fails closed below.
            hostKey = inputData.getString(K_HOST) ?: "",
            deviceId = inputData.getString(K_DEVICE) ?: return Result.failure(),
            sessionId = inputData.getString(K_SESSION) ?: return Result.failure(),
            text = inputData.getString(K_TEXT) ?: return Result.failure(),
            requestId = inputData.getString(K_REQUEST) ?: return Result.failure(),
            createdAtMillis = inputData.getLong(K_CREATED, 0L),
        )
        val graph = (applicationContext as AgentDeckApplication).graph
        val notifier = Notifier(applicationContext)
        if (reply.hostKey.isEmpty()) {
            notifier.showOrphanReplyFailure("orphan|${reply.sessionId}", reply.text)
            return Result.failure()
        }
        val delivery = ReplyDelivery(
            resolve = { host, device -> graph.credentials.pairing(host, device) },
            send = { identity, sid, text, rid -> graph.repository.callSaved(identity) { send(sid, text, rid) } },
            drafts = graph.drafts,
            status = { r, title, text, failed ->
                val label = graph.credentials.host(r.hostKey)?.label ?: r.hostKey
                notifier.showReplyStatus(NotificationTarget(r.hostKey, r.deviceId, label), r.sessionId, title, text, failed)
            },
        )
        return when (delivery.deliver(reply, runAttemptCount)) {
            ReplyDelivery.Outcome.SENT -> Result.success()
            ReplyDelivery.Outcome.RETRY -> Result.retry()
            ReplyDelivery.Outcome.FAILED -> Result.failure()
        }
    }

    companion object {
        const val TAG = "notification-reply"
        const val K_SESSION = "session"
        const val K_HOST = "host"
        const val K_DEVICE = "device"
        const val K_TEXT = "text"
        const val K_REQUEST = "request"
        const val K_CREATED = "created"
    }
}
