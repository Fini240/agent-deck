package de.finn.agentdeck.core.push

import de.finn.agentdeck.core.model.AgentDeckJson
import de.finn.agentdeck.core.model.Progress
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import java.time.Instant
import java.time.format.DateTimeParseException
import kotlin.math.roundToInt

/** Notification channels the app registers. IDs are stable; users manage them in system settings. */
enum class Channel(val id: String) {
    PROGRESS("agent_progress"),
    RESULTS("agent_results"),
    INPUT("agent_input"),
    REPLIES("reply_status"),
}

sealed interface ProgressBar {
    /** Real ratio from reported numbers. [max] and [value] are scaled ints for NotificationCompat. */
    data class Determinate(val max: Int, val value: Int, val label: String) : ProgressBar
    data object Indeterminate : ProgressBar
    data object None : ProgressBar
}

enum class TapTarget { SESSION }

/**
 * What to show for one decrypted push. Pure data so the decisions are unit-testable without
 * Android; the app layer only maps this onto NotificationCompat.
 */
data class NotificationPlan(
    val sessionId: String,
    val eventId: String,
    val channel: Channel,
    val title: String,
    val text: String,
    val silent: Boolean,
    val ongoing: Boolean,
    val onlyAlertOnce: Boolean,
    val progress: ProgressBar,
    val allowReply: Boolean,
    val requestPromotedOngoing: Boolean,
    val timeoutMillis: Long? = null,
    val tapTarget: TapTarget = TapTarget.SESSION,
)

object NotificationPlanner {
    private const val PROGRESS_SCALE = 1000
    /** Session id the Mac uses for `POST /push/test`; it has no chat to reply to. */
    const val TEST_SESSION_ID = "agentdeck-test"

    fun plan(p: PushPayload): NotificationPlan? {
        val kind = p.kind
        if (kind == PushKind.UNKNOWN) return null
        val title = p.title.ifBlank { defaultTitle(kind, p.agent) }
        return when (kind) {
            PushKind.PROGRESS -> {
                val bar = progressBar(p.progress)
                val parts = listOfNotNull(
                    p.stage?.takeIf { it.isNotBlank() },
                    p.body.takeIf { it.isNotBlank() && it != p.stage },
                    (bar as? ProgressBar.Determinate)?.label,
                )
                NotificationPlan(
                    sessionId = p.sessionId, eventId = p.eventId, channel = Channel.PROGRESS,
                    title = title, text = parts.joinToString(" · ").ifBlank { "Working" },
                    silent = true, ongoing = true, onlyAlertOnce = true, progress = bar,
                    allowReply = p.canReply, requestPromotedOngoing = true,
                    timeoutMillis = (p.validForSeconds ?: 180).coerceIn(60, 7260) * 1000,
                )
            }
            PushKind.COMPLETED, PushKind.ERROR -> NotificationPlan(
                sessionId = p.sessionId, eventId = p.eventId, channel = Channel.RESULTS,
                title = title, text = p.body.ifBlank { if (kind == PushKind.ERROR) "Stopped with an error" else "Finished" },
                silent = false, ongoing = false, onlyAlertOnce = false, progress = ProgressBar.None,
                allowReply = p.canReply && p.sessionId != TEST_SESSION_ID, requestPromotedOngoing = false,
            )
            // Input requests can be permission prompts: open the app, never offer an inline answer.
            PushKind.INPUT -> NotificationPlan(
                sessionId = p.sessionId, eventId = p.eventId, channel = Channel.INPUT,
                title = title, text = p.body.ifBlank { "Waiting for your input" },
                silent = false, ongoing = false, onlyAlertOnce = false, progress = ProgressBar.None,
                allowReply = false, requestPromotedOngoing = false,
            )
            PushKind.UNKNOWN -> null
        }
    }

    /** Determinate only when the reported numbers form a real ratio; otherwise indeterminate. */
    fun progressBar(progress: Progress?): ProgressBar {
        val fraction = progress?.fraction ?: return ProgressBar.Indeterminate
        val percent = (fraction * 100).roundToInt()
        val unit = progress.unit?.takeIf { it.isNotBlank() }?.let { " $it" } ?: ""
        val label = "${formatNumber(progress.current)} of ${formatNumber(progress.total)}$unit ($percent%)"
        return ProgressBar.Determinate(PROGRESS_SCALE, (fraction * PROGRESS_SCALE).roundToInt(), label)
    }

    private fun formatNumber(v: Double): String =
        if (v == Math.floor(v) && !v.isInfinite()) v.toLong().toString() else String.format(java.util.Locale.ROOT, "%.1f", v)

    private fun defaultTitle(kind: PushKind, agent: String): String {
        val who = when (agent) { "claude" -> "Claude Code"; "codex" -> "Codex"; "" -> "Agent"; else -> agent }
        return when (kind) {
            PushKind.PROGRESS -> "$who is working"
            PushKind.COMPLETED -> "$who finished"
            PushKind.ERROR -> "$who hit an error"
            PushKind.INPUT -> "$who needs input"
            PushKind.UNKNOWN -> who
        }
    }
}

/** Where [NotificationGate] keeps its decisions so they survive process death. */
interface GateStore {
    fun load(): String?
    fun save(value: String)
}

/**
 * Drops duplicate pushes and stale updates. FCM can redeliver and reorder messages (and the Mac
 * sends them from a thread pool), so a delayed progress message must never overwrite a
 * completion that already arrived, and an older result must not replace a newer one. FCM often
 * starts a fresh process per message, so the per-session state is persisted via [store].
 * Callers with several hosts pass a per-pairing scope so their IDs never collide.
 */
class NotificationGate(
    private val maxRemembered: Int = 200,
    private val minProgressIntervalMillis: Long = 1000,
    private val store: GateStore? = null,
) {
    @Serializable
    private data class Snapshot(
        val seen: List<String> = emptyList(),
        val terminal: Map<String, String> = emptyMap(),
        val progress: Map<String, String> = emptyMap(),
    )

    private val seen = LinkedHashSet<String>()
    /** Latest result/input timestamp per session; insertion order = least recently updated first. */
    private val lastTerminal = LinkedHashMap<String, Instant>()
    private val lastProgressTs = LinkedHashMap<String, Instant>()
    private val lastProgressShown = HashMap<String, Long>()
    private val lastProgressStage = HashMap<String, String?>()

    init {
        restore()
    }

    @Synchronized
    fun shouldShow(p: PushPayload, nowMillis: Long, scope: String = ""): Boolean {
        // Same event or session IDs from two hosts/pairings are different things: key by [scope].
        val session = if (scope.isEmpty()) p.sessionId else "$scope|${p.sessionId}"
        val event = if (scope.isEmpty()) p.eventId else "$scope|${p.eventId}"
        if (!seen.add(event)) return false
        while (seen.size > maxRemembered) seen.remove(seen.first())
        val ts = parseInstant(p.timestamp)
        if (p.kind == PushKind.PROGRESS && ts != null && nowMillis - ts.toEpochMilli() > (p.validForSeconds ?: 180).coerceIn(60, 7260) * 1000) {
            persist()
            return false
        }
        val show = when (p.kind) {
            PushKind.PROGRESS -> {
                val terminal = lastTerminal[session]
                val newest = lastProgressTs[session]
                val last = lastProgressShown[session]
                val stageChanged = lastProgressStage[session] != p.stage
                when {
                    terminal != null && (ts == null || !ts.isAfter(terminal)) -> false
                    ts != null && newest != null && ts.isBefore(newest) -> false
                    last != null && !stageChanged && nowMillis - last < minProgressIntervalMillis -> false
                    else -> {
                        lastProgressShown[session] = nowMillis
                        lastProgressStage[session] = p.stage
                        if (ts != null) put(lastProgressTs, session, ts)
                        true
                    }
                }
            }
            PushKind.COMPLETED, PushKind.ERROR, PushKind.INPUT -> {
                val terminal = lastTerminal[session]
                val newestProgress = lastProgressTs[session]
                if (ts != null && ((terminal != null && ts.isBefore(terminal)) || (newestProgress != null && ts.isBefore(newestProgress)))) {
                    false
                } else {
                    if (ts != null) put(lastTerminal, session, ts)
                    lastProgressShown.remove(session)
                    true
                }
            }
            PushKind.UNKNOWN -> false
        }
        persist()
        return show
    }

    private fun put(map: LinkedHashMap<String, Instant>, key: String, value: Instant) {
        map.remove(key)
        map[key] = value
        while (map.size > maxRemembered) map.remove(map.keys.first())
    }

    private fun persist() {
        val s = store ?: return
        val snap = Snapshot(
            seen = seen.toList(),
            terminal = lastTerminal.mapValues { it.value.toString() },
            progress = lastProgressTs.mapValues { it.value.toString() },
        )
        s.save(AgentDeckJson.encodeToString(Snapshot.serializer(), snap))
    }

    private fun restore() {
        val raw = store?.load() ?: return
        val snap = try {
            AgentDeckJson.decodeFromString(Snapshot.serializer(), raw)
        } catch (_: SerializationException) {
            return
        } catch (_: IllegalArgumentException) {
            return
        }
        seen.addAll(snap.seen.takeLast(maxRemembered))
        snap.terminal.forEach { (k, v) -> parseInstant(v)?.let { put(lastTerminal, k, it) } }
        snap.progress.forEach { (k, v) -> parseInstant(v)?.let { put(lastProgressTs, k, it) } }
    }

    private fun parseInstant(value: String?): Instant? = try {
        value?.let { Instant.parse(it) }
    } catch (_: DateTimeParseException) {
        null
    }
}
