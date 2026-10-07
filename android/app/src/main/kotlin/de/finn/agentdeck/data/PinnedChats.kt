package de.finn.agentdeck.data

import android.content.SharedPreferences
import androidx.core.content.edit
import org.json.JSONArray

/** Device-local bookmarks, isolated by paired Mac URL. No chat text is stored. */
class PinnedChats(private val prefs: SharedPreferences) {
    fun get(server: String?): Set<String> {
        if (server == null) return emptySet()
        return runCatching {
            val values = JSONArray(prefs.getString("pins:$server", "[]"))
            (0 until values.length()).map { values.getString(it) }.toSet()
        }.getOrDefault(emptySet())
    }

    fun toggle(server: String?, id: String) {
        if (server == null) return
        val current = get(server)
        val next = if (id in current) current - id else (current + id).toList().takeLast(100).toSet()
        prefs.edit { putString("pins:$server", JSONArray(next.toList()).toString()) }
    }
}
