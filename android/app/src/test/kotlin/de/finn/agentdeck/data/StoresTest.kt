package de.finn.agentdeck.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.finn.agentdeck.core.api.ServerUrl
import de.finn.agentdeck.notify.ReplyReceiver
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** XOR "cipher" standing in for AndroidKeyStore, which Robolectric cannot provide. */
private class FakeCipher : SecretCipher {
    override fun encrypt(plain: ByteArray) = byteArrayOf(1) + plain.map { (it.toInt() xor 0x5a).toByte() }
    override fun decrypt(sealed: ByteArray): ByteArray {
        require(sealed[0] == 1.toByte()) { "wrong key" }
        return sealed.drop(1).map { (it.toInt() xor 0x5a).toByte() }.toByteArray()
    }
}

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class StoresTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val key32 = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="

    @Test
    fun credentialsAreSealedAtRestAndRestored() {
        val prefs = ctx.getSharedPreferences("cred-test", Context.MODE_PRIVATE)
        val store = CredentialStore(prefs, FakeCipher())
        store.save(ServerUrl.trusted("https://mac.ts.net:8443"), "Mac", "dev1", "secret-token-value", key32)
        val raw = prefs.all.values.joinToString()
        assertFalse("token must not be stored in plain text", raw.contains("secret-token-value"))
        assertFalse("push key must not be stored in plain text", raw.contains(key32))
        val again = CredentialStore(prefs, FakeCipher()).current()!!
        assertEquals("secret-token-value", again.token)
        assertEquals(32, again.pushKey.size)
        assertArrayEquals(ByteArray(32) { it.toByte() }, again.pushKey)
        assertFalse(again.toString().contains("secret-token-value"))
    }

    @Test
    fun undecryptableCredentialsAskToPairAgain() {
        val prefs = ctx.getSharedPreferences("cred-test2", Context.MODE_PRIVATE)
        CredentialStore(prefs, FakeCipher()).save(ServerUrl.trusted("https://mac.ts.net"), "Mac", "dev1", "t", key32)
        val broken = object : SecretCipher {
            override fun encrypt(plain: ByteArray) = plain
            override fun decrypt(sealed: ByteArray): ByteArray = throw javax.crypto.AEADBadTagException("key gone")
        }
        val store = CredentialStore(prefs, broken)
        assertNull(store.current())
        assertTrue(store.loadError!!.contains("Pair again"))
    }

    @Test
    fun invalidPushKeyIsRejectedBeforeSaving() {
        val prefs = ctx.getSharedPreferences("cred-test3", Context.MODE_PRIVATE)
        val store = CredentialStore(prefs, FakeCipher())
        runCatching { store.save(ServerUrl.trusted("https://mac.ts.net"), "Mac", "d", "t", "AAAA") }
        assertNull(store.current())
        assertTrue(prefs.all.isEmpty())
    }

    @Test
    fun draftRetryReusesRequestIdUntilTextChanges() {
        val drafts = DraftStore(ctx.getSharedPreferences("draft-test", Context.MODE_PRIVATE))
        drafts.setText("s1", "hello")
        val first = drafts.requestIdFor("s1", "hello")
        assertEquals("retry after failure is idempotent", first, drafts.requestIdFor("s1", "hello"))
        drafts.setText("s1", "hello world")
        assertNotEquals(first, drafts.requestIdFor("s1", "hello world"))
        drafts.clearIfSent("s1", "something else")
        assertEquals("hello world", drafts.get("s1").text)
        drafts.clearIfSent("s1", "hello world")
        assertEquals("", drafts.get("s1").text)
    }

    @Test
    fun failedNotificationReplyBecomesDraft() {
        val drafts = DraftStore(ctx.getSharedPreferences("draft-test2", Context.MODE_PRIVATE))
        drafts.setText("s", "typed in app")
        drafts.restoreFailedReply("s", "from shade")
        assertEquals("typed in app\nfrom shade", drafts.get("s").text)
        drafts.restoreFailedReply("s", "from shade")
        assertEquals("typed in app\nfrom shade", drafts.get("s").text)
    }

    @Test
    fun replyWorkIsUniquePerSessionAndText() {
        assertEquals(ReplyReceiver.uniqueName("https://mac.ts.net", "d", "s", "yes"), ReplyReceiver.uniqueName("https://mac.ts.net", "d", "s", "yes"))
        assertNotEquals(ReplyReceiver.uniqueName("https://mac.ts.net", "d", "s", "yes"), ReplyReceiver.uniqueName("https://mac.ts.net", "d", "t", "yes"))
        assertNotEquals(ReplyReceiver.uniqueName("https://mac.ts.net", "d", "s", "yes"), ReplyReceiver.uniqueName("https://mac.ts.net", "d", "s", "no"))
        assertNotEquals(ReplyReceiver.uniqueName("https://mac.ts.net", "d", "s", "yes"), ReplyReceiver.uniqueName("https://mac.ts.net", "e", "s", "yes"))
    }
}
