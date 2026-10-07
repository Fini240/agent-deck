package de.finn.agentdeck.core.push

import de.finn.agentdeck.core.model.Progress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationPlannerTest {
    private fun payload(
        type: String,
        progress: Progress? = null,
        eventId: String = "e-$type",
        ts: String? = "2026-10-06T21:00:00Z",
        stage: String? = null,
        session: String = "s1",
    ) = PushPayload(eventId, type, session, "", "body", "claude", ts, progress, stage, canReply = true)

    @Test
    fun readOnlyAndLegacyNotificationsCannotOfferReply() {
        for (type in listOf("progress", "completed", "error")) {
            assertFalse(NotificationPlanner.plan(payload(type).copy(canReply = false))!!.allowReply)
            assertFalse(NotificationPlanner.plan(PushPayload("e", type, "s"))!!.allowReply)
        }
        assertTrue(NotificationPlanner.plan(payload("progress"))!!.allowReply)
    }

    @Test
    fun progressExpiresIfTheHelperStopsRefreshingIt() {
        assertEquals(180_000L, NotificationPlanner.plan(payload("progress"))!!.timeoutMillis)
        assertEquals(660_000L, NotificationPlanner.plan(payload("progress").copy(validForSeconds = 660))!!.timeoutMillis)
        assertNull(NotificationPlanner.plan(payload("completed"))!!.timeoutMillis)
    }

    @Test
    fun progressIsSilentOngoingAndOnlyAlertsOnce() {
        val plan = NotificationPlanner.plan(payload("progress", Progress(3.0, 10.0, "files")))!!
        assertEquals(Channel.PROGRESS, plan.channel)
        assertTrue(plan.silent)
        assertTrue(plan.ongoing)
        assertTrue(plan.onlyAlertOnce)
        val bar = plan.progress as ProgressBar.Determinate
        assertEquals(1000, bar.max)
        assertEquals(300, bar.value)
        assertEquals("3 of 10 files (30%)", bar.label)
    }

    @Test
    fun unknownTotalsAreIndeterminateNeverInvented() {
        assertEquals(ProgressBar.Indeterminate, NotificationPlanner.progressBar(null))
        assertEquals(ProgressBar.Indeterminate, NotificationPlanner.progressBar(Progress(5.0, 0.0, null)))
        assertEquals(ProgressBar.Indeterminate, NotificationPlanner.progressBar(Progress(12.0, 10.0, null)))
        assertEquals(ProgressBar.Indeterminate, NotificationPlanner.progressBar(Progress(-1.0, 10.0, null)))
        val plan = NotificationPlanner.plan(payload("progress", null))!!
        assertEquals(ProgressBar.Indeterminate, plan.progress)
        assertFalse(plan.text.contains("%"))
    }

    @Test
    fun completionAndErrorAlertAndAllowReply() {
        for (type in listOf("completed", "error")) {
            val plan = NotificationPlanner.plan(payload(type))!!
            assertEquals(Channel.RESULTS, plan.channel)
            assertFalse(plan.silent)
            assertFalse(plan.ongoing)
            assertTrue(plan.allowReply)
            assertEquals(ProgressBar.None, plan.progress)
        }
    }

    @Test
    fun inputAlertsButOnlyOpensTheApp() {
        val plan = NotificationPlanner.plan(payload("input"))!!
        assertEquals(Channel.INPUT, plan.channel)
        assertFalse(plan.silent)
        assertFalse("permission prompts must not be answerable from the shade", plan.allowReply)
        assertEquals(TapTarget.SESSION, plan.tapTarget)
    }

    @Test
    fun macTestPushHasNoReplyAction() {
        val plan = NotificationPlanner.plan(payload("completed", session = NotificationPlanner.TEST_SESSION_ID))!!
        assertFalse(plan.allowReply)
        assertFalse(plan.silent)
    }

    @Test
    fun unknownTypeIsIgnored() {
        assertNull(NotificationPlanner.plan(payload("something-new")))
    }

    @Test
    fun previousTurnsDelayedCompletionCannotOverwriteNewWork() {
        val gate = NotificationGate()
        assertTrue(gate.shouldShow(payload("progress", eventId = "new-work", ts = "2026-10-06T21:06:00Z"), 0))
        assertFalse(gate.shouldShow(payload("completed", eventId = "old-done", ts = "2026-10-06T21:05:00Z"), 1))
    }

    @Test
    fun expiredProgressCannotReappearAfterTheMacWentOffline() {
        val ts = java.time.Instant.parse("2026-10-06T21:00:00Z").toEpochMilli()
        assertFalse(NotificationGate().shouldShow(payload("progress"), ts + 181_000))
        assertTrue(NotificationGate().shouldShow(payload("progress"), ts + 1000))
    }

    @Test
    fun gateDropsDuplicatesAndStaleProgressAfterCompletion() {
        val gate = NotificationGate()
        val done = payload("completed", ts = "2026-10-06T21:05:00Z")
        assertTrue(gate.shouldShow(done, 0))
        assertFalse("FCM redelivery", gate.shouldShow(done, 1))
        val late = payload("progress", eventId = "late", ts = "2026-10-06T21:04:00Z")
        assertFalse("progress older than completion", gate.shouldShow(late, 2))
        val next = payload("progress", eventId = "next", ts = "2026-10-06T21:06:00Z")
        assertTrue("new work after completion", gate.shouldShow(next, 3))
    }

    @Test
    fun gateThrottlesSameStageProgressButNotStageChanges() {
        val gate = NotificationGate(minProgressIntervalMillis = 1000)
        assertTrue(gate.shouldShow(payload("progress", eventId = "a", stage = "Build"), 0))
        assertFalse(gate.shouldShow(payload("progress", eventId = "b", stage = "Build"), 500))
        assertTrue(gate.shouldShow(payload("progress", eventId = "c", stage = "Test"), 600))
        assertTrue(gate.shouldShow(payload("progress", eventId = "d", stage = "Test"), 1700))
    }

    private class MemoryStore : GateStore {
        var value: String? = null
        override fun load() = value
        override fun save(value: String) { this.value = value }
    }

    @Test
    fun gateDropsOlderResultArrivingAfterNewerOne() {
        val gate = NotificationGate()
        assertTrue(gate.shouldShow(payload("input", eventId = "i", ts = "2026-10-06T21:05:00Z"), 0))
        assertFalse("older completion must not replace the newer input request", gate.shouldShow(payload("completed", eventId = "c", ts = "2026-10-06T21:04:00Z"), 1))
        assertTrue("same-instant result is still shown", gate.shouldShow(payload("error", eventId = "e", ts = "2026-10-06T21:05:00Z"), 2))
        assertTrue(gate.shouldShow(payload("completed", eventId = "c2", ts = "2026-10-06T21:06:00Z"), 3))
    }

    @Test
    fun gateDropsProgressOlderThanShownProgress() {
        val gate = NotificationGate(minProgressIntervalMillis = 0)
        assertTrue(gate.shouldShow(payload("progress", eventId = "a", ts = "2026-10-06T21:05:00Z", stage = "Test"), 0))
        assertFalse(gate.shouldShow(payload("progress", eventId = "b", ts = "2026-10-06T21:04:00Z", stage = "Build"), 5_000))
        assertTrue(gate.shouldShow(payload("progress", eventId = "c", ts = "2026-10-06T21:06:00Z", stage = "Test"), 10_000))
    }

    @Test
    fun gateRemembersResultsAcrossProcessDeath() {
        val store = MemoryStore()
        val done = payload("completed", eventId = "done", ts = "2026-10-06T21:05:00Z")
        assertTrue(NotificationGate(store = store).shouldShow(done, 0))
        // FCM starts a fresh process for the next message.
        val fresh = NotificationGate(store = store)
        assertFalse("redelivered completion", fresh.shouldShow(done, 0))
        assertFalse(
            "delayed progress must not bring back an ongoing notification after completion",
            fresh.shouldShow(payload("progress", eventId = "late", ts = "2026-10-06T21:04:59Z"), 0),
        )
        assertTrue(NotificationGate(store = store).shouldShow(payload("progress", eventId = "new", ts = "2026-10-06T21:07:00Z"), 0))
        assertFalse(
            "older progress after a restart",
            NotificationGate(store = store).shouldShow(payload("progress", eventId = "old", ts = "2026-10-06T21:06:00Z"), 0),
        )
    }

    @Test
    fun gateIgnoresDamagedStateAndStaysBounded() {
        val store = MemoryStore().apply { value = "{not json" }
        val gate = NotificationGate(maxRemembered = 3, store = store)
        for (i in 0 until 10) assertTrue(gate.shouldShow(payload("completed", eventId = "e$i", session = "s$i"), 0))
        val saved = store.value!!
        assertFalse(saved.contains("\"s0\""))
        assertTrue(saved.contains("\"s9\""))
        assertFalse(saved.contains("\"e0\""))
    }
}
