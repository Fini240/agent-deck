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
import de.finn.agentdeck.data.DraftStore
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
        // Notifications posted before replies carried the pairing fall back to the current one.
        val deviceId = intent.getStringExtra(EXTRA_DEVICE)
            ?: (context.applicationContext as AgentDeckApplication).graph.credentials.current()?.deviceId
            ?: ""
        // Update right away so the shade stops showing the reply spinner.
        Notifier(context).showReplyStatus(sessionId, "Sending reply…", text, failed = false)
        enqueue(context, deviceId, sessionId, text)
    }

    companion object {
        const val ACTION_REPLY = "de.finn.agentdeck.action.REPLY"
        const val EXTRA_SESSION = "session_id"
        const val EXTRA_DEVICE = "device_id"
        const val KEY_TEXT = "reply_text"

        /**
         * One unique work per (pairing, session, text): a duplicate broadcast while the first is
         * pending is dropped, and retries reuse the same requestId so the Mac applies the reply at
         * most once.
         */
        fun enqueue(context: Context, deviceId: String, sessionId: String, text: String, nowMillis: Long = System.currentTimeMillis()) {
            val request = OneTimeWorkRequestBuilder<ReplyWorker>()
                .setInputData(
                    workDataOf(
                        ReplyWorker.K_SESSION to sessionId,
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
            WorkManager.getInstance(context).enqueueUniqueWork(uniqueName(deviceId, sessionId, text), ExistingWorkPolicy.KEEP, request)
        }

        fun uniqueName(deviceId: String, sessionId: String, text: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest("$deviceId\u0000$sessionId\u0000$text".toByteArray())
            return "reply-" + digest.take(12).joinToString("") { "%02x".format(it) }
        }
    }
}

/** A notification reply as queued by [ReplyReceiver]. */
data class QueuedReply(val deviceId: String, val sessionId: String, val text: String, val requestId: String, val createdAtMillis: Long)

/**
 * Decides what happens to one attempt of a notification reply. Kept apart from WorkManager so the
 * failure paths are testable: every outcome either sends once, retries with the same requestId,
 * or ends with the text saved as a draft and a visible "not sent" notification.
 */
class ReplyDelivery(
    private val currentDeviceId: () -> String?,
    private val send: suspend (sessionId: String, text: String, requestId: String) -> Unit,
    private val drafts: DraftStore,
    private val status: (sessionId: String, title: String, text: String, failed: Boolean) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    enum class Outcome { SENT, RETRY, FAILED }

    suspend fun deliver(r: QueuedReply, attempt: Int): Outcome {
        if (currentDeviceId() != r.deviceId) {
            // Removed or paired again since the reply was typed: never send it through another
            // pairing (possibly another Mac). The requestId is meaningless there, so drop it.
            return fail(r, "This phone was removed or paired again before the reply was sent.", requestId = null)
        }
        if (clock() - r.createdAtMillis > MAX_AGE_MILLIS) {
            // Sends always interrupt; a reply stuck offline this long would cut into newer work.
            return fail(r, "It could not be sent within ${MAX_AGE_MILLIS / 60_000} minutes.", r.requestId)
        }
        return try {
            send(r.sessionId, r.text, r.requestId)
            status(r.sessionId, "Reply sent", r.text, false)
            Outcome.SENT
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            if (e.isTransient && attempt < MAX_ATTEMPTS) {
                status(r.sessionId, "Reply waiting to send", "Will retry: ${e.message}", false)
                Outcome.RETRY
            } else {
                if (e is ApiException.Http && e.code == "delivery_uncertain") {
                    drafts.restoreFailedReply(r.sessionId, r.text, r.requestId)
                    status(r.sessionId, "Delivery not confirmed", "Your reply may already be in the chat. Check it before sending another message. Your text is saved as a draft.", true)
                    Outcome.FAILED
                } else fail(r, e.message.orEmpty(), r.requestId)
            }
        } catch (e: Exception) {
            fail(r, "Unexpected error (${e.javaClass.simpleName}).", r.requestId)
        }
    }

    private fun fail(r: QueuedReply, reason: String, requestId: String?): Outcome {
        drafts.restoreFailedReply(r.sessionId, r.text, requestId)
        status(r.sessionId, "Reply not sent", "$reason Your text is saved as a draft in the app.", true)
        return Outcome.FAILED
    }

    companion object {
        const val MAX_ATTEMPTS = 5
        const val MAX_AGE_MILLIS = 30 * 60_000L
    }
}

/** Sends a notification reply to the same session with `interrupt = true`. */
class ReplyWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val reply = QueuedReply(
            deviceId = inputData.getString(K_DEVICE) ?: return Result.failure(),
            sessionId = inputData.getString(K_SESSION) ?: return Result.failure(),
            text = inputData.getString(K_TEXT) ?: return Result.failure(),
            requestId = inputData.getString(K_REQUEST) ?: return Result.failure(),
            createdAtMillis = inputData.getLong(K_CREATED, 0L),
        )
        val graph = (applicationContext as AgentDeckApplication).graph
        val notifier = Notifier(applicationContext)
        val delivery = ReplyDelivery(
            currentDeviceId = { graph.credentials.current()?.deviceId },
            send = { sid, text, rid ->
                val identity = graph.credentials.current()?.takeIf { it.deviceId == reply.deviceId } ?: throw ApiException.NotPaired()
                graph.repository.callFor(identity) { send(sid, text, rid) }
            },
            drafts = graph.drafts,
            status = notifier::showReplyStatus,
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
        const val K_DEVICE = "device"
        const val K_TEXT = "text"
        const val K_REQUEST = "request"
        const val K_CREATED = "created"
    }
}
