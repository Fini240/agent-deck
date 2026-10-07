package de.finn.agentdeck.notify

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import de.finn.agentdeck.core.api.ApiException
import de.finn.agentdeck.data.DraftStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class ReplyDeliveryTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val drafts = DraftStore(ctx.getSharedPreferences("reply-delivery-test", Context.MODE_PRIVATE).also { it.edit().clear().commit() })
    private var device: String? = "dev1"
    private var now = 1_000_000L
    private val sent = mutableListOf<Triple<String, String, String>>()
    private val shown = mutableListOf<Pair<String, Boolean>>()
    private var failure: Exception? = null

    private val delivery = ReplyDelivery(
        currentDeviceId = { device },
        send = { s, t, r -> failure?.let { throw it }; sent += Triple(s, t, r) },
        drafts = drafts,
        status = { _, title, _, failed -> shown += title to failed },
        clock = { now },
    )
    private val reply = QueuedReply("dev1", "s1", "continue", "req-1", now)

    @Test
    fun sendsOnceWithTheQueuedRequestId() = runTest {
        assertEquals(ReplyDelivery.Outcome.SENT, delivery.deliver(reply, 0))
        assertEquals(listOf(Triple("s1", "continue", "req-1")), sent)
        assertEquals("Reply sent" to false, shown.last())
        assertEquals("", drafts.get("s1").text)
    }

    @Test
    fun transientFailuresRetryThenBecomeADraftThatReusesTheRequestId() = runTest {
        failure = ApiException.Network(IOException("timeout"))
        assertEquals(ReplyDelivery.Outcome.RETRY, delivery.deliver(reply, 0))
        assertEquals(ReplyDelivery.Outcome.FAILED, delivery.deliver(reply, ReplyDelivery.MAX_ATTEMPTS))
        assertEquals("Reply not sent" to true, shown.last())
        assertEquals("continue", drafts.get("s1").text)
        // The last attempt may have reached the Mac: sending the draft must be deduplicated there.
        assertEquals("req-1", drafts.requestIdFor("s1", "continue"))
    }

    @Test
    fun revokedDeviceBecomesDraft() = runTest {
        failure = ApiException.Unauthorized("Pair again.")
        assertEquals(ReplyDelivery.Outcome.FAILED, delivery.deliver(reply, 0))
        assertEquals("continue", drafts.get("s1").text)
    }

    @Test
    fun unexpectedExceptionsStillSaveTheDraftAndReportFailure() = runTest {
        failure = IllegalStateException("boom")
        assertEquals(ReplyDelivery.Outcome.FAILED, delivery.deliver(reply, 0))
        assertEquals("Reply not sent" to true, shown.last())
        assertEquals("continue", drafts.get("s1").text)
    }

    @Test
    fun cancellationIsNotTurnedIntoAFailure() = runTest {
        failure = CancellationException("stopped by WorkManager")
        try {
            delivery.deliver(reply, 0)
            fail("expected cancellation")
        } catch (_: CancellationException) {
        }
        assertEquals("", drafts.get("s1").text)
        assertTrue(shown.isEmpty())
    }

    @Test
    fun replyIsNeverSentThroughADifferentPairing() = runTest {
        device = "dev2" // phone removed and paired again (maybe with another Mac)
        assertEquals(ReplyDelivery.Outcome.FAILED, delivery.deliver(reply, 0))
        device = null // or removed and not paired
        assertEquals(ReplyDelivery.Outcome.FAILED, delivery.deliver(reply, 0))
        assertTrue(sent.isEmpty())
        assertEquals("continue", drafts.get("s1").text)
        assertNotEquals("req-1", drafts.requestIdFor("s1", "continue"))
    }

    @Test
    fun replyStuckOfflineTooLongDoesNotInterruptNewerWork() = runTest {
        now += ReplyDelivery.MAX_AGE_MILLIS + 1
        assertEquals(ReplyDelivery.Outcome.FAILED, delivery.deliver(reply, 0))
        assertTrue(sent.isEmpty())
        assertEquals("continue", drafts.get("s1").text)
    }

    @Test
    fun failedReplyMergesIntoExistingDraftWithoutReusingItsRequestId() {
        drafts.setText("s1", "typed in app")
        drafts.restoreFailedReply("s1", "continue", "req-1")
        assertEquals("typed in app\ncontinue", drafts.get("s1").text)
        assertNotEquals("req-1", drafts.requestIdFor("s1", "typed in app\ncontinue"))
    }

    @Test
    fun duplicateReplyBroadcastIsQueuedOncePerPairing() {
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx)
        val wm = WorkManager.getInstance(ctx)
        ReplyReceiver.enqueue(ctx, "dev1", "s1", "yes")
        ReplyReceiver.enqueue(ctx, "dev1", "s1", "yes")
        ReplyReceiver.enqueue(ctx, "dev2", "s1", "yes")
        val pending = wm.getWorkInfosByTag(ReplyWorker.TAG).get().filter { it.state == WorkInfo.State.ENQUEUED }
        assertEquals(2, pending.size)
    }
}
