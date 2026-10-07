package de.finn.agentdeck.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class PinnedChatsTest {
    @Test fun bookmarksPersistAndStayOnTheirMac() {
        val prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("pins-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val store = PinnedChats(prefs)
        store.toggle("https://first.test", "same-session-id")
        assertEquals(setOf("same-session-id"), PinnedChats(prefs).get("https://first.test"))
        assertTrue(store.get("https://second.test").isEmpty())
        store.toggle(null, "ignored")
        assertTrue(store.get(null).isEmpty())
        store.toggle("https://first.test", "same-session-id")
        assertTrue(store.get("https://first.test").isEmpty())
        repeat(105) { store.toggle("https://first.test", "chat-$it") }
        assertEquals(100, store.get("https://first.test").size)
        assertFalse("chat-0" in store.get("https://first.test"))
        assertTrue("chat-104" in store.get("https://first.test"))
    }
}
