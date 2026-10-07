package de.finn.agentdeck.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.UUID

/**
 * Unsent follow-ups per host and session, persisted so they survive errors, process death and
 * reconnects. Keys come from [key]: the same session ID on two hosts never shares a draft.
 *
 * Each draft carries the requestId of its last send attempt, bound to the pairing that made it:
 * retrying the same text through the same pairing reuses it (the helper deduplicates), editing the
 * text or pairing the host again starts a new request.
 */
class DraftStore(private val prefs: SharedPreferences) {
    data class Draft(val text: String, val requestId: String?)

    private val _external = MutableSharedFlow<String>(extraBufferCapacity = 16)
    /** Draft keys whose text changed outside the composer (a failed notification reply). */
    val externalChanges: SharedFlow<String> = _external.asSharedFlow()

    /** [pairingKey] limits the returned requestId to attempts made through that pairing. */
    fun get(key: String, pairingKey: String? = null): Draft {
        val text = prefs.getString(textKey(key), null).orEmpty()
        val stored = prefs.getString(reqKey(key), null)
        val requestId = stored?.let { v ->
            val sep = v.indexOf(SEP)
            val owner = if (sep < 0) null else v.substring(0, sep)
            val id = if (sep < 0) v else v.substring(sep + 1)
            id.takeIf { pairingKey == null || owner == pairingKey }
        }
        return Draft(text, requestId)
    }

    fun setText(key: String, text: String) {
        if (prefs.getString(textKey(key), null).orEmpty() == text) return
        prefs.edit {
            if (text.isEmpty()) remove(textKey(key)) else putString(textKey(key), text)
            remove(reqKey(key))
        }
    }

    /** Stable request ID for the current text and pairing; created on first send attempt. */
    fun requestIdFor(key: String, text: String, pairingKey: String = "legacy"): String {
        val current = get(key, pairingKey)
        if (current.text == text && current.requestId != null) return current.requestId
        val id = UUID.randomUUID().toString()
        prefs.edit(commit = true) {
            putString(textKey(key), text)
            putString(reqKey(key), "$pairingKey$SEP$id")
        }
        return id
    }

    /** Clears only if the draft still holds the text that was sent (the user may have typed more). */
    fun clearIfSent(key: String, sentText: String) {
        if (get(key).text == sentText) prefs.edit(commit = true) { remove(textKey(key)); remove(reqKey(key)) }
    }

    /**
     * A notification reply that could not be delivered becomes (part of) the in-app draft of its own
     * host. When the draft is exactly the reply, its [requestId] is kept for [pairingKey]: the last
     * attempt may have reached the helper before failing, and sending again must not apply it twice.
     */
    fun restoreFailedReply(key: String, text: String, requestId: String? = null, pairingKey: String? = null) {
        val existing = get(key).text
        if (requestId != null && pairingKey != null && (existing.isBlank() || existing == text)) {
            prefs.edit(commit = true) {
                putString(textKey(key), text)
                putString(reqKey(key), "$pairingKey$SEP$requestId")
            }
            _external.tryEmit(key)
            return
        }
        val merged = when {
            existing.isBlank() -> text
            existing.contains(text) -> existing
            else -> "$existing\n$text"
        }
        setText(key, merged)
        _external.tryEmit(key)
    }

    /**
     * Moves single-host drafts (≤ 0.2.3, keyed by session ID only) into [hostKey]'s namespace. Their
     * request IDs were made by [pairingKey]. Never overwrites a draft that already exists there.
     */
    fun migrateLegacy(hostKey: String, pairingKey: String) {
        val legacy = prefs.all.keys.filter { it.startsWith(L_TEXT) || it.startsWith(L_REQ) }
        if (legacy.isEmpty()) return
        prefs.edit(commit = true) {
            legacy.filter { it.startsWith(L_TEXT) }.forEach { old ->
                val sid = old.removePrefix(L_TEXT)
                val k = key(hostKey, sid, pairingKey)
                val text = prefs.getString(old, null)
                if (text != null && !prefs.contains(textKey(k))) {
                    putString(textKey(k), text)
                    prefs.getString(L_REQ + sid, null)?.let { putString(reqKey(k), "$pairingKey$SEP$it") }
                }
            }
            legacy.forEach { remove(it) }
        }
    }

    private fun textKey(key: String) = "t2:$key"
    private fun reqKey(key: String) = "r2:$key"

    companion object {
        private const val SEP = '\u0000'
        private const val L_TEXT = "text:"
        private const val L_REQ = "req:"

        /** Draft key for [sessionId] on the host with canonical URL [hostKey]. */
        fun key(hostKey: String, sessionId: String, pairingKey: String? = null) = "${pairingKey ?: HostKeys.host(hostKey)}/$sessionId"

        fun create(context: Context) = DraftStore(context.getSharedPreferences("drafts", Context.MODE_PRIVATE))
    }
}
