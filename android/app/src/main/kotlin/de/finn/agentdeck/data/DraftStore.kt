package de.finn.agentdeck.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.UUID

/**
 * Unsent follow-ups per session, persisted so they survive errors, process death and
 * reconnects. Each draft carries the requestId of its last send attempt: retrying the same text
 * reuses it (the helper deduplicates), editing the text starts a new request.
 */
class DraftStore(private val prefs: SharedPreferences) {
    data class Draft(val text: String, val requestId: String?)

    private val _external = MutableSharedFlow<String>(extraBufferCapacity = 16)
    /** Session IDs whose draft changed outside the composer (a failed notification reply). */
    val externalChanges: SharedFlow<String> = _external.asSharedFlow()

    fun get(sessionId: String) = Draft(prefs.getString(textKey(sessionId), null).orEmpty(), prefs.getString(reqKey(sessionId), null))

    fun setText(sessionId: String, text: String) {
        if (prefs.getString(textKey(sessionId), null).orEmpty() == text) return
        prefs.edit {
            if (text.isEmpty()) remove(textKey(sessionId)) else putString(textKey(sessionId), text)
            remove(reqKey(sessionId))
        }
    }

    /** Stable request ID for the current text; created on first send attempt. */
    fun requestIdFor(sessionId: String, text: String): String {
        val current = get(sessionId)
        if (current.text == text && current.requestId != null) return current.requestId
        val id = UUID.randomUUID().toString()
        prefs.edit(commit = true) {
            putString(textKey(sessionId), text)
            putString(reqKey(sessionId), id)
        }
        return id
    }

    /** Clears only if the draft still holds the text that was sent (the user may have typed more). */
    fun clearIfSent(sessionId: String, sentText: String) {
        if (get(sessionId).text == sentText) prefs.edit(commit = true) { remove(textKey(sessionId)); remove(reqKey(sessionId)) }
    }

    /**
     * A notification reply that could not be delivered becomes (part of) the in-app draft. When the
     * draft is exactly the reply, its [requestId] is kept: the last attempt may have reached the Mac
     * before failing, and sending the draft again must not apply it twice.
     */
    fun restoreFailedReply(sessionId: String, text: String, requestId: String? = null) {
        val existing = get(sessionId).text
        if (requestId != null && (existing.isBlank() || existing == text)) {
            prefs.edit(commit = true) {
                putString(textKey(sessionId), text)
                putString(reqKey(sessionId), requestId)
            }
            _external.tryEmit(sessionId)
            return
        }
        val merged = when {
            existing.isBlank() -> text
            existing.contains(text) -> existing
            else -> "$existing\n$text"
        }
        setText(sessionId, merged)
        _external.tryEmit(sessionId)
    }

    private fun textKey(id: String) = "text:$id"
    private fun reqKey(id: String) = "req:$id"

    companion object {
        fun create(context: Context) = DraftStore(context.getSharedPreferences("drafts", Context.MODE_PRIVATE))
    }
}
