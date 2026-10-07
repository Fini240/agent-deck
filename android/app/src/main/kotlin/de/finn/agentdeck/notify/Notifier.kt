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

/**
 * Maps [NotificationPlan]s onto Android notifications. One notification per session: the session
 * ID is the notification tag and part of every PendingIntent's identity, so two sessions can never
 * replace each other's notification or reply target.
 */
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

    /** [deviceId] is the pairing the push was decrypted for; a reply is only sent through that pairing. */
    fun show(plan: NotificationPlan, agentLabel: String, deviceId: String) {
        if (!canPost()) return
        val b = NotificationCompat.Builder(context, plan.channel.id)
            .setSmallIcon(R.drawable.ic_stat_agent)
            .setContentTitle(plan.title)
            .setContentText(plan.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(plan.text))
            .setSubText(agentLabel)
            .setContentIntent(openSession(plan.sessionId))
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
        if (plan.allowReply) b.addAction(replyAction(plan.sessionId, deviceId))
        post(plan.sessionId, SESSION_ID, b)
    }

    /** Replaces the session notification while a direct reply is in flight / after it resolves. */
    fun showReplyStatus(sessionId: String, title: String, text: String, failed: Boolean) {
        if (!canPost()) return
        val b = NotificationCompat.Builder(context, Channel.REPLIES.id)
            .setSmallIcon(R.drawable.ic_stat_agent)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openSession(sessionId))
            .setAutoCancel(true)
            .setSilent(!failed)
        post(sessionId, SESSION_ID, b)
    }

    /** Local-only test: proves this phone can display alerts, not that the Mac can reach it. */
    fun showLocalTest(): Boolean {
        if (!canPost()) return false
        val b = NotificationCompat.Builder(context, Channel.RESULTS.id)
            .setSmallIcon(R.drawable.ic_stat_agent)
            .setContentTitle("Local test notification")
            .setContentText("Shown by this phone. It does not test delivery from the Mac.")
            .setAutoCancel(true)
            .setContentIntent(openApp())
        post(null, TEST_ID, b)
        return true
    }

    fun cancel(sessionId: String) = manager.cancel(sessionId, SESSION_ID)

    /** Pairing changed: notifications of the old pairing must not stay around with reply actions. */
    fun cancelAll() = manager.cancelAll()

    private fun post(tag: String?, id: Int, b: NotificationCompat.Builder) {
        try {
            manager.notify(tag, id, b.build())
        } catch (_: SecurityException) {
            // Permission revoked between the check and the call; nothing else to do.
        }
    }

    private fun replyAction(sessionId: String, deviceId: String): NotificationCompat.Action {
        val input = RemoteInput.Builder(ReplyReceiver.KEY_TEXT).setLabel("Reply to this session").build()
        val intent = Intent(context, ReplyReceiver::class.java)
            .setAction(ReplyReceiver.ACTION_REPLY)
            .setData(sessionUri(sessionId))
            .putExtra(ReplyReceiver.EXTRA_SESSION, sessionId)
            .putExtra(ReplyReceiver.EXTRA_DEVICE, deviceId)
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

    private fun openSession(sessionId: String): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java)
            .setAction(MainActivity.ACTION_OPEN_SESSION)
            .setData(sessionUri(sessionId))
            .putExtra(MainActivity.EXTRA_SESSION_ID, sessionId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        private const val TEST_ID = 1
        /** Session notifications share this ID; the session ID tag makes each one distinct. */
        const val SESSION_ID = 2

        /**
         * Makes PendingIntents distinct per session: extras are ignored by Intent.filterEquals, so
         * without this FLAG_UPDATE_CURRENT would retarget another session's reply/open action.
         */
        fun sessionUri(sessionId: String): Uri = Uri.Builder().scheme("agentdeck-session").opaquePart(sessionId).build()
    }
}
