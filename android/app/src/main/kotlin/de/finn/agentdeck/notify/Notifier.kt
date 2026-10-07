package de.finn.agentdeck.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import de.finn.agentdeck.MainActivity
import de.finn.agentdeck.R
import de.finn.agentdeck.core.push.Channel
import de.finn.agentdeck.core.push.NotificationPlan
import de.finn.agentdeck.core.push.ProgressBar
import de.finn.agentdeck.data.HostKeys

/**
 * The saved pairing a notification belongs to. Every tag, PendingIntent identity and reply target
 * is namespaced by it, so the same session ID on two hosts (or two pairings of one host) never
 * replaces the other's notification or sends through the wrong pairing.
 */
data class NotificationTarget(val hostKey: String, val deviceId: String, val hostLabel: String) {
    val pairingKey: String get() = HostKeys.pairing(hostKey, deviceId)
    fun tag(sessionId: String) = "$pairingKey|$sessionId"
}

/** Maps [NotificationPlan]s onto Android notifications: one notification per pairing and session. */
class Notifier(private val context: Context) {
    private val manager = NotificationManagerCompat.from(context)

    fun ensureChannels() {
        val nm = context.getSystemService(NotificationManager::class.java)
        fun channel(c: Channel, name: Int, desc: Int, importance: Int, silent: Boolean) =
            NotificationChannel(c.id, context.getString(name), importance).apply {
                description = context.getString(desc)
                if (silent) {
                    setSound(null, null)
                    enableVibration(false)
                } else {
                    enableVibration(true)
                }
            }
        nm.createNotificationChannels(
            listOf(
                channel(Channel.PROGRESS, R.string.channel_progress, R.string.channel_progress_desc, NotificationManager.IMPORTANCE_LOW, silent = true),
                channel(Channel.RESULTS, R.string.channel_results, R.string.channel_results_desc, NotificationManager.IMPORTANCE_HIGH, silent = false),
                channel(Channel.INPUT, R.string.channel_input, R.string.channel_input_desc, NotificationManager.IMPORTANCE_HIGH, silent = false),
                channel(Channel.REPLIES, R.string.channel_replies, R.string.channel_replies_desc, NotificationManager.IMPORTANCE_DEFAULT, silent = true),
            ),
        )
    }

    fun canPost(): Boolean =
        manager.areNotificationsEnabled() && (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            )

    /** Channels the user switched off in system settings. */
    fun blockedChannels(): List<Channel> {
        val nm = context.getSystemService(NotificationManager::class.java)
        return Channel.entries.filter { nm.getNotificationChannel(it.id)?.importance == NotificationManager.IMPORTANCE_NONE }
    }

    /** [target] is the pairing the push was decrypted for; a reply is only sent through that pairing. */
    fun show(plan: NotificationPlan, agentLabel: String, target: NotificationTarget) {
        if (!canPost()) return
        val b = NotificationCompat.Builder(context, plan.channel.id)
            .setSmallIcon(R.drawable.ic_stat_agent)
            .setContentTitle(plan.title)
            .setContentText(plan.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(plan.text))
            .setSubText("$agentLabel · ${target.hostLabel}")
            .setContentIntent(openSession(target, plan.sessionId))
            .setOngoing(plan.ongoing)
            .setOnlyAlertOnce(plan.onlyAlertOnce)
            .setSilent(plan.silent)
            .setAutoCancel(!plan.ongoing)
            .setCategory(
                when (plan.channel) {
                    Channel.PROGRESS -> NotificationCompat.CATEGORY_PROGRESS
                    Channel.INPUT -> NotificationCompat.CATEGORY_REMINDER
                    else -> NotificationCompat.CATEGORY_STATUS
                },
            )
            .setPriority(if (plan.silent) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_HIGH)
        when (val bar = plan.progress) {
            is ProgressBar.Determinate -> b.setProgress(bar.max, bar.value, false)
            ProgressBar.Indeterminate -> b.setProgress(0, 0, true)
            ProgressBar.None -> Unit
        }
        if (plan.requestPromotedOngoing) b.setRequestPromotedOngoing(true)
        plan.timeoutMillis?.let { b.setTimeoutAfter(it) }
        if (plan.allowReply) b.addAction(replyAction(target, plan.sessionId))
        post(target.tag(plan.sessionId), SESSION_ID, b)
    }

    /** Replaces the session notification while a direct reply is in flight / after it resolves. */
    fun showReplyStatus(target: NotificationTarget, sessionId: String, title: String, text: String, failed: Boolean) {
        if (!canPost()) return
        val b = NotificationCompat.Builder(context, Channel.REPLIES.id)
            .setSmallIcon(R.drawable.ic_stat_agent)
            .setContentTitle(title)
            .setContentText(text)
            .setSubText(target.hostLabel)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openSession(target, sessionId))
            .setAutoCancel(true)
            .setSilent(!failed)
        post(target.tag(sessionId), SESSION_ID, b)
    }

    /**
     * A reply whose device can't be established (a notification from an older app version, or one
     * whose pairing is gone). Shows the text so nothing is lost; opens the app without a chat.
     */
    fun showOrphanReplyFailure(tag: String, text: String) {
        if (!canPost()) return
        val body = "The device this notification came from was removed or paired again, so nothing was sent. Your text: $text"
        val b = NotificationCompat.Builder(context, Channel.REPLIES.id)
            .setSmallIcon(R.drawable.ic_stat_agent)
            .setContentTitle("Reply not sent")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(openApp())
            .setAutoCancel(true)
        post(tag, SESSION_ID, b)
    }

    /** Local-only test: proves this phone can display alerts, not that the Mac can reach it. */
    fun showLocalTest(): Boolean {
        if (!canPost()) return false
        val b = NotificationCompat.Builder(context, Channel.RESULTS.id)
            .setSmallIcon(R.drawable.ic_stat_agent)
            .setContentTitle("Local test notification")
            .setContentText("Shown by this phone. It does not test delivery from a paired device.")
            .setAutoCancel(true)
            .setContentIntent(openApp())
        post(null, TEST_ID, b)
        return true
    }

    fun cancel(target: NotificationTarget, sessionId: String) = manager.cancel(target.tag(sessionId), SESSION_ID)

    /** A pairing was removed or replaced: its notifications must not stay around with reply actions. */
    fun cancelPairing(pairingKey: String) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.activeNotifications.filter { it.tag?.startsWith("$pairingKey|") == true }.forEach { manager.cancel(it.tag, it.id) }
    }

    fun cancelAll() = manager.cancelAll()

    private fun post(tag: String?, id: Int, b: NotificationCompat.Builder) {
        try {
            manager.notify(tag, id, b.build())
        } catch (_: SecurityException) {
            // Permission revoked between the check and the call; nothing else to do.
        }
    }

    private fun replyAction(target: NotificationTarget, sessionId: String): NotificationCompat.Action {
        val input = RemoteInput.Builder(ReplyReceiver.KEY_TEXT).setLabel("Reply on ${target.hostLabel}").build()
        val intent = Intent(context, ReplyReceiver::class.java)
            .setAction(ReplyReceiver.ACTION_REPLY)
            .setData(sessionUri(target, sessionId))
            .putExtra(ReplyReceiver.EXTRA_SESSION, sessionId)
            .putExtra(ReplyReceiver.EXTRA_HOST, target.hostKey)
            .putExtra(ReplyReceiver.EXTRA_DEVICE, target.deviceId)
        val pi = PendingIntent.getBroadcast(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        return NotificationCompat.Action.Builder(R.drawable.ic_stat_agent, "Reply", pi)
            .addRemoteInput(input)
            .setAllowGeneratedReplies(false)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
            .build()
    }

    private fun openSession(target: NotificationTarget, sessionId: String): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java)
            .setAction(MainActivity.ACTION_OPEN_SESSION)
            .setData(sessionUri(target, sessionId))
            .putExtra(MainActivity.EXTRA_SESSION_ID, sessionId)
            .putExtra(MainActivity.EXTRA_HOST, target.hostKey)
            .putExtra(MainActivity.EXTRA_DEVICE, target.deviceId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        private const val TEST_ID = 1
        /** Session notifications share this ID; the pairing + session tag makes each one distinct. */
        const val SESSION_ID = 2

        /**
         * Makes PendingIntents distinct per pairing and session: extras are ignored by
         * Intent.filterEquals, so without this FLAG_UPDATE_CURRENT would retarget another
         * notification's reply/open action.
         */
        fun sessionUri(target: NotificationTarget, sessionId: String): Uri =
            Uri.Builder().scheme("agentdeck-session").opaquePart(target.tag(sessionId)).build()
    }
}
