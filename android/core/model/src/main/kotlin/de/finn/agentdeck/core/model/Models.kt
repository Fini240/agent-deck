package de.finn.agentdeck.core.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json

/** One JSON configuration for every module: tolerant of extra fields, strict about types. */
val AgentDeckJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    coerceInputValues = true
}

/** Session status. Unrecognised server values decode to [UNKNOWN] instead of failing. */
@Serializable(with = SessionStatusSerializer::class)
enum class SessionStatus(val wire: String) {
    WORKING("working"),
    IDLE("idle"),
    NEEDS_INPUT("needs_input"),
    COMPLETED("completed"),
    ERROR("error"),
    UNKNOWN("unknown"),
    OFFLINE("offline");

    companion object {
        fun fromWire(value: String?): SessionStatus = entries.firstOrNull { it.wire == value } ?: UNKNOWN
    }
}

internal object SessionStatusSerializer : KSerializer<SessionStatus> {
    override val descriptor = PrimitiveSerialDescriptor("SessionStatus", PrimitiveKind.STRING)
    override fun deserialize(decoder: Decoder) = SessionStatus.fromWire(decoder.decodeString())
    override fun serialize(encoder: Encoder, value: SessionStatus) = encoder.encodeString(value.wire)
}

/**
 * Measured progress. Unknown totals are represented by a null [Progress] on the owning object,
 * never by an invented value. [fraction] is null whenever the numbers cannot form a real ratio.
 */
@Serializable
data class Progress(
    val current: Double,
    val total: Double,
    val unit: String? = null,
) {
    val fraction: Float?
        get() = if (total > 0.0 && current >= 0.0 && current <= total && !current.isNaN() && !total.isNaN()) {
            (current / total).toFloat()
        } else {
            null
        }
}

@Serializable
data class Capabilities(
    val send: Boolean = false,
    val interrupt: Boolean = false,
    val approve: Boolean = false,
    val stop: Boolean = false,
)

@Serializable
data class Session(
    val id: String,
    val agent: String,
    val nativeId: String? = null,
    val title: String = "",
    val cwd: String = "",
    val model: String? = null,
    val status: SessionStatus = SessionStatus.UNKNOWN,
    val stage: String? = null,
    val progress: Progress? = null,
    val updatedAt: String? = null,
    val managed: Boolean = false,
    val capabilities: Capabilities = Capabilities(),
    val lastMessage: String? = null,
    val unread: Int = 0,
    // Optional agent-group fields (2026-10-07). Old servers omit them; defaults keep behaviour.
    val parentSessionId: String? = null,
    val agentName: String? = null,
    val agentRole: String? = null,
    val isSubagent: Boolean = false,
    val task: String? = null,
    val projectRoot: String? = null,
    val statusEvidence: String? = null,
    val live: Boolean? = null,
    val canResume: Boolean = false,
) {
    /** Grouping key: the server's normalized project root, else the working directory. */
    val projectKey: String get() = (projectRoot?.takeIf { it.isNotBlank() } ?: cwd).trimEnd('/').ifBlank { "(no folder)" }
    val isActive: Boolean get() = status == SessionStatus.WORKING || status == SessionStatus.NEEDS_INPUT
}

@Serializable
data class Message(
    val id: String,
    val role: String,
    val text: String = "",
    val timestamp: String? = null,
    val toolName: String? = null,
)

@Serializable
data class ApprovalChoice(val id: String, val label: String)

@Serializable
data class Approval(
    val id: String,
    val sessionId: String,
    val title: String = "",
    val detail: String = "",
    val choices: List<ApprovalChoice> = emptyList(),
    val createdAt: String? = null,
)

@Serializable
data class ModelOption(
    val id: String,
    val label: String = id,
    val description: String? = null,
    val source: String? = null,
)

@Serializable
data class AgentModels(
    val id: String,
    val name: String = id,
    val available: Boolean = false,
    val version: String? = null,
    val models: List<ModelOption> = emptyList(),
    val modelSource: String? = null,
    val modelRefreshedAt: String? = null,
    val error: String? = null,
)

@Serializable
data class ModelsResponse(
    val agents: List<AgentModels> = emptyList(),
    val refreshedAt: String? = null,
)

@Serializable
data class DefaultModels(
    val claude: String? = null,
    val codex: String? = null,
)

@Serializable
data class ServerSettings(
    val allowedWorkspaces: List<String> = emptyList(),
    val defaultAgent: String? = null,
    val defaultModels: DefaultModels = DefaultModels(),
    val notifyOn: List<String> = emptyList(),
    val progressIntervalSeconds: Int? = null,
    val keepAwakeMode: String? = null,
)

/** PATCH body: only fields that are set are sent (explicitNulls = false). */
@Serializable
data class SettingsPatch(
    val defaultAgent: String? = null,
    val defaultModels: DefaultModels? = null,
    val notifyOn: List<String>? = null,
    val progressIntervalSeconds: Int? = null,
    val keepAwakeMode: String? = null,
)

// ---- Request / response envelopes -------------------------------------------------------

@Serializable data class HealthResponse(val status: String = "", val version: String? = null)
@Serializable data class PairRequest(val code: String, val deviceName: String, val fcmToken: String? = null)
@Serializable data class PairResponse(val deviceId: String, val token: String, val pushKey: String, val serverName: String = "")
@Serializable data class SessionsResponse(val sessions: List<Session> = emptyList())
@Serializable data class SessionResponse(val session: Session)
@Serializable data class MessagesResponse(val messages: List<Message> = emptyList())
@Serializable data class TerminalResponse(val text: String = "", val available: Boolean = true)
@Serializable data class ApprovalsResponse(val approvals: List<Approval> = emptyList())
@Serializable data class SettingsResponse(val settings: ServerSettings = ServerSettings())
@Serializable data class StartSessionRequest(val agent: String, val model: String? = null, val cwd: String, val prompt: String, val requestId: String? = null)
@Serializable data class SendRequest(val text: String, val interrupt: Boolean = true, val requestId: String)
@Serializable data class StopRequest(val requestId: String)
@Serializable data class ApprovalResponseRequest(val choiceId: String, val requestId: String)
@Serializable data class InputKeyRequest(val key: String)
@Serializable data class FcmTokenRequest(val fcmToken: String)
@Serializable data class AcceptedResponse(val accepted: Boolean = false, val sessionId: String? = null)
@Serializable data class ResumeRequest(val requestId: String)

/** GET /api/v1/status (server extension beyond the original contract). */
@Serializable
data class ServerStatus(
    val version: String? = null,
    val push: PushServerStatus = PushServerStatus(),
    val keepAwake: KeepAwakeStatus? = null,
    val providers: ComponentStatus = ComponentStatus(),
    val terminal: ComponentStatus = ComponentStatus(),
    val monitor: MonitorStatus? = null,
    val sseClients: Int? = null,
)
@Serializable data class PushServerStatus(val available: Boolean = false, val reason: String? = null, val projectId: String? = null, val devicesWithToken: Int = 0)
@Serializable data class KeepAwakeStatus(val held: Boolean = false, val mode: String? = null, val error: String? = null)
@Serializable data class ComponentStatus(val available: Boolean = false, val error: String? = null)
@Serializable data class MonitorStatus(val refreshedAt: String? = null, val error: String? = null)

/** POST /api/v1/push/test result: per-device delivery counters. */
@Serializable data class PushTestResult(val ok: Int = 0, val error: Int = 0, val unavailable: Int = 0, val unregistered: Int = 0, val skipped: Int = 0)
@Serializable data class PushTestResponse(val result: PushTestResult = PushTestResult())

@Serializable data class ErrorBody(val code: String = "unknown", val message: String = "")
@Serializable data class ErrorEnvelope(val error: ErrorBody? = null)

/** Terminal fallback keys accepted by POST /sessions/{id}/input. */
enum class TerminalKey(val wire: String) { UP("up"), DOWN("down"), ENTER("enter"), ESCAPE("escape"), TAB("tab") }

/** Event pushed over GET /api/v1/events (SSE, `event: update`). */
@Serializable
data class ServerEvent(
    val type: String,
    val sessionId: String? = null,
)

object Roles {
    const val USER = "user"
    const val ASSISTANT = "assistant"
    const val TOOL = "tool"
    const val SYSTEM = "system"
}

object Agents {
    const val CLAUDE = "claude"
    const val CODEX = "codex"

    fun displayName(agent: String): String = when (agent) {
        CLAUDE -> "Claude Code"
        CODEX -> "Codex"
        else -> agent.replaceFirstChar { it.uppercase() }
    }
}
