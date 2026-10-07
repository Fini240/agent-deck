package de.finn.agentdeck.notify

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.finn.agentdeck.MainActivity
import de.finn.agentdeck.core.push.NotificationPlanner
import de.finn.agentdeck.core.push.PushPayload
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class NotifierTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val nm = ctx.getSystemService(NotificationManager::class.java)
    private val notifier = Notifier(ctx)

    @Before
    fun setUp() {
        shadowOf(ctx as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        notifier.ensureChannels()
    }

    private fun show(sessionId: String, type: String = "completed", device: String = "dev1") =
        notifier.show(NotificationPlanner.plan(PushPayload("e-$sessionId-$type", type, sessionId, "", "b", "claude", null, canReply = true))!!, "Claude Code", device)

    private fun notificationFor(sessionId: String): Notification =
        nm.activeNotifications.single { it.tag == sessionId }.notification

    @Test
    fun sessionsWithCollidingHashCodesKeepTheirOwnNotificationAndTargets() {
        check("Aa".hashCode() == "BB".hashCode())
        show("Aa")
        show("BB")
        assertEquals(2, nm.activeNotifications.size)
        for (sid in listOf("Aa", "BB")) {
            val n = notificationFor(sid)
            val open = shadowOf(n.contentIntent).savedIntent
            assertEquals(sid, open.getStringExtra(MainActivity.EXTRA_SESSION_ID))
            val reply = shadowOf(n.actions.single().actionIntent).savedIntent
            assertEquals("reply goes to the session it was shown for", sid, reply.getStringExtra(ReplyReceiver.EXTRA_SESSION))
            assertEquals("dev1", reply.getStringExtra(ReplyReceiver.EXTRA_DEVICE))
        }
    }

    @Test
    fun progressCompletionAndReplyStatusReplaceEachOtherPerSession() {
        show("s1", type = "progress")
        show("s1", type = "completed")
        notifier.showReplyStatus("s1", "Sending reply…", "ok", failed = false)
        show("s2", type = "progress")
        assertEquals(2, nm.activeNotifications.size)
        assertEquals("Sending reply…", notificationFor("s1").extras.getString(Notification.EXTRA_TITLE))
        notifier.cancel("s2")
        assertEquals(listOf("s1"), nm.activeNotifications.map { it.tag })
    }

    @Test
    fun replyActionCarriesThePairingThePushWasDecryptedFor() {
        show("s1", device = "dev-new")
        val reply = shadowOf(notificationFor("s1").actions.single().actionIntent).savedIntent
        assertEquals("dev-new", reply.getStringExtra(ReplyReceiver.EXTRA_DEVICE))
        notifier.cancelAll()
        assertEquals(0, nm.activeNotifications.size)
    }
}
