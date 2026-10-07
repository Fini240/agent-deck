package de.finn.agentdeck.core.push

import de.finn.agentdeck.core.model.AgentDeckJson
import de.finn.agentdeck.core.model.Progress
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable

/** Plaintext of an encrypted push (contract v1). */
@Serializable
data class PushPayload(
    val eventId: String,
    val type: String,
    val sessionId: String,
    val title: String = "",
    val body: String = "",
    val agent: String = "",
    val timestamp: String? = null,
    val progress: Progress? = null,
    val stage: String? = null,
    val canReply: Boolean = false,
    val validForSeconds: Long? = null,
) {
    val kind: PushKind get() = PushKind.fromWire(type)

    companion object {
        fun parse(json: String): PushPayload = try {
            AgentDeckJson.decodeFromString(serializer(), json)
        } catch (e: SerializationException) {
            throw PushDecryptException(PushDecryptException.Reason.BAD_PAYLOAD, "Push payload is not valid JSON for contract v1", e)
        } catch (e: IllegalArgumentException) {
            throw PushDecryptException(PushDecryptException.Reason.BAD_PAYLOAD, "Push payload is not valid JSON for contract v1", e)
        }

        /** Decrypt and parse in one step. */
        fun open(key: ByteArray, data: Map<String, String>): PushPayload = parse(PushCrypto.decrypt(key, data))
    }
}

enum class PushKind(val wire: String) {
    PROGRESS("progress"), COMPLETED("completed"), ERROR("error"), INPUT("input"), UNKNOWN("");

    companion object {
        fun fromWire(value: String): PushKind = entries.firstOrNull { it.wire == value && it != UNKNOWN } ?: UNKNOWN
    }
}
