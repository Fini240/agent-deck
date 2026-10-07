package de.finn.agentdeck.data

import android.app.Application
import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.finn.agentdeck.core.api.ApiException
import de.finn.agentdeck.core.api.ServerUrl
import de.finn.agentdeck.core.push.NotificationGate
import de.finn.agentdeck.core.push.PushCrypto
import de.finn.agentdeck.core.push.PushPayload
import de.finn.agentdeck.notify.*
import de.finn.agentdeck.push.AgentDeckMessagingService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class MultiHostTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val cipher = object : SecretCipher {
        override fun encrypt(plain: ByteArray) = byteArrayOf(7) + plain.map { (it.toInt() xor 90).toByte() }
        override fun decrypt(sealed: ByteArray) = sealed.drop(1).map { (it.toInt() xor 90).toByte() }.toByteArray()
    }
    private fun prefs() = context.getSharedPreferences(UUID.randomUUID().toString(), Context.MODE_PRIVATE)
    private val mac = ServerUrl.trusted("https://mac.test:10443")
    private val zima = ServerUrl.trusted("https://zima.test:10443")
    private fun key(n: Int) = Base64.encodeToString(ByteArray(32) { n.toByte() }, Base64.NO_WRAP)
    private fun store(): CredentialStore = CredentialStore(prefs(), cipher).apply {
        save(mac, "Mac", "same-device-id", "mac-token", key(1))
        save(zima, "ZimaOS", "same-device-id", "zima-token", key(2))
    }

    @Test fun addingSelectingRenamingRemovingAndRestartKeepOtherPairings() {
        val p = prefs(); val s = CredentialStore(p, cipher)
        s.save(mac, "Mac", "m", "mac-token", key(1)); val old = s.current()!!
        s.save(zima, "ZimaOS", "z", "zima-token", key(2))
        assertEquals(2, s.hosts.value.size); assertTrue(s.isSaved(old))
        assertTrue(s.select(mac.value)); s.rename(zima.value, "Server")
        val again = CredentialStore(p, cipher)
        assertEquals(old, again.current()); assertEquals("Server", again.host(zima.value)!!.label)
        again.remove(mac.value); assertEquals(zima.value, again.current()!!.hostKey)
        assertFalse(again.isSaved(old)); assertEquals("zima-token", again.current()!!.token)
        assertFalse(p.all.toString().contains("zima-token"))
    }

    @Test fun legacyPairingAndDraftsMigrateOnlyToOriginalMac() {
        val p = prefs()
        p.edit().putString("server", mac.value).putString("server_name", "Mac").putString("device_id", "m")
            .putString("token_sealed", Base64.encodeToString(cipher.encrypt("mac-token".toByteArray()), Base64.NO_WRAP))
            .putString("push_key_sealed", Base64.encodeToString(cipher.encrypt(ByteArray(32) { 1 }), Base64.NO_WRAP)).commit()
        val s = CredentialStore(p, cipher); val old = s.current()!!
        assertEquals("mac-token", old.token); assertFalse(p.contains("server"))
        val dp = prefs(); dp.edit().putString("text:same", "old draft").putString("req:same", "old-request").commit()
        val drafts = DraftStore(dp); drafts.migrateLegacy(old.hostKey, old.pairingKey)
        s.save(zima, "ZimaOS", "z", "other", key(2))
        assertEquals("old draft", drafts.get(DraftStore.key(old.hostKey, "same", old.pairingKey)).text)
        assertEquals("old-request", drafts.requestIdFor(DraftStore.key(old.hostKey, "same", old.pairingKey), "old draft", old.pairingKey))
        assertEquals("", drafts.get(DraftStore.key(zima.value, "same", s.current()!!.pairingKey)).text)
    }

    @Test fun reparingReplacesOnlySameUrlAndOldPairIsNoLongerSaved() {
        val s = store(); val oldMac = s.host(mac.value)!!.credentials!!; val oldZima = s.current()!!
        s.save(zima, "ZimaOS", "new-z", "new-token", key(3))
        assertEquals(2, s.hosts.value.size); assertTrue(s.isSaved(oldMac)); assertFalse(s.isSaved(oldZima))
    }

    @Test fun maximumEightRejectsBeforeChangingSavedHosts() {
        val s = CredentialStore(prefs(), cipher)
        repeat(8) { s.save(ServerUrl.trusted("https://h$it.test"), "Host $it", "d$it", "t$it", key(it)) }
        val old = s.current()
        try { s.save(ServerUrl.trusted("https://ninth.test"), "9", "d9", "t9", key(9)); fail() } catch (_: TooManyHosts) { }
        assertEquals(8, s.hosts.value.size); assertEquals(old, s.current())
    }

    @Test fun draftsAndRequestIdsAreSeparateForSameSessionAndAfterRePairing() {
        val s = store(); val a = s.host(mac.value)!!.credentials!!; val b = s.current()!!
        val d = DraftStore(prefs()); val ak = DraftStore.key(a.hostKey, "same", a.pairingKey); val bk = DraftStore.key(b.hostKey, "same", b.pairingKey)
        d.setText(ak, "Mac reply"); d.setText(bk, "Zima reply")
        val ar = d.requestIdFor(ak, "Mac reply", a.pairingKey); val br = d.requestIdFor(bk, "Zima reply", b.pairingKey)
        assertNotEquals(ar, br); d.clearIfSent(bk, "Zima reply"); assertEquals("Mac reply", d.get(ak).text)
        assertEquals("", d.get(DraftStore.key(a.hostKey, "same", HostKeys.pairing(a.hostKey, "new"))).text)
    }

    @Test fun inactiveEncryptedPushRoutesAndRemovedKeyCannotDecrypt() {
        val s = store(); val a = s.host(mac.value)!!.credentials!!
        val data = PushCrypto.encrypt(a.pushKey, ByteArray(12) { 8 }, """{"eventId":"same","type":"completed","sessionId":"same","agent":"claude","title":"Done","body":"ok"}""")
        val routed = AgentDeckMessagingService.route(s.usable(), data)!!
        assertEquals(a, routed.first); assertEquals("same", routed.second.sessionId)
        s.remove(a.hostKey); assertNull(AgentDeckMessagingService.route(s.usable(), data))
    }

    @Test fun sameEventAndSessionIdsFromDifferentHostsBothPassGate() {
        val s = store(); val a = s.host(mac.value)!!.credentials!!; val b = s.current()!!
        val gate = NotificationGate(); val event = PushPayload("same", "completed", "same", "Done", "ok", "claude", "2026-10-07T18:00:00Z")
        assertTrue(gate.shouldShow(event, 1L, a.pairingKey)); assertTrue(gate.shouldShow(event, 1L, b.pairingKey))
        assertFalse(gate.shouldShow(event, 1L, a.pairingKey))
        assertNotEquals(Notifier.sessionUri(NotificationTarget(a.hostKey, a.deviceId, "Mac"), "same"), Notifier.sessionUri(NotificationTarget(b.hostKey, b.deviceId, "Zima"), "same"))
    }

    @Test fun ambiguousLegacyReplyOriginFailsClosedWhileExplicitHostRoutes() {
        val s = store()
        assertNull(ReplyReceiver.origin(s.hosts.value, null, "same-device-id"))
        assertNull(ReplyReceiver.origin(s.hosts.value, mac.value, null))
        assertEquals(mac.value, ReplyReceiver.origin(s.hosts.value, mac.value, "same-device-id")!!.hostKey)
    }

    @Test fun inactiveDirectReplySendsOnlyToItsOriginalHostAndToken() = runTest {
        val s = store(); val a = s.host(mac.value)!!.credentials!!
        val requests = mutableListOf<Request>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("{\"accepted\":true}".toResponseBody("application/json".toMediaType())).build()
        }.build()
        val repo = AgentDeckRepository(s.credentials, backgroundScope, http, isSaved = s::isSaved)
        val reply = QueuedReply(a.hostKey, a.deviceId, "same", "continue", "stable-request", 0)
        val d = DraftStore(prefs())
        val delivery = ReplyDelivery(s::pairing, { identity, sid, text, rid -> repo.callSaved(identity) { send(sid, text, rid) } }, d, { _, _, _, _ -> }, { 1 })
        assertEquals(ReplyDelivery.Outcome.SENT, delivery.deliver(reply, 0))
        assertEquals("mac.test", requests.single().url.host); assertEquals("Bearer mac-token", requests.single().header("Authorization"))
        assertEquals(zima.value, s.current()!!.hostKey)
        s.remove(a.hostKey)
        assertEquals(ReplyDelivery.Outcome.FAILED, delivery.deliver(reply, 1)); assertEquals(1, requests.size)
        assertEquals("continue", d.get(reply.draftKey).text)
        assertEquals("", d.get(DraftStore.key(zima.value, "same", s.current()!!.pairingKey)).text)
    }

    @Test fun staleForegroundActionIsRejectedBeforeItCanReachNewHost() = runTest {
        val s = store(); val original = s.current()!!; s.select(mac.value)
        val repo = AgentDeckRepository(s.credentials, backgroundScope, isSaved = s::isSaved)
        try { repo.callFor(original) { sessions() }; fail() } catch (_: ApiException.HostChanged) { }
    }

    @Test fun rotatedTokenRegistersEveryHostDespiteAnOfflineMacAndShowsOnlySelectedFacts() = runTest {
        val s = store(); val requests = java.util.Collections.synchronizedList(mutableListOf<Request>())
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request()
            if (chain.request().url.host == "mac.test") throw java.io.IOException("offline")
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("{\"accepted\":true}".toResponseBody("application/json".toMediaType())).build()
        }.build()
        val repo = AgentDeckRepository(s.credentials, backgroundScope, http, isSaved = s::isSaved)
        val registrar = de.finn.agentdeck.push.PushRegistrar(context, prefs(), configured = true, tokenProvider = { "new-fcm-token" })
        registrar.restore(s.current()); registrar.onTokenRotated()
        val failures = registrar.ensureRegisteredAll(repo, s.usable())
        assertEquals(1, failures.size); assertEquals(setOf("mac.test", "zima.test"), requests.map { it.url.host }.toSet())
        assertTrue(registrar.status.value.connected)
        registrar.restore(s.host(mac.value)!!.credentials)
        assertFalse(registrar.status.value.connected)
        assertTrue(registrar.status.value.registration is de.finn.agentdeck.push.PushStatus.Registration.Failed)
        registrar.restore(s.current()); assertTrue(registrar.status.value.connected)
    }

    @Test fun delayedResponsesAfterSwitchOrRemovalAreRejectedWithoutRedirectingRequest() = runTest {
        val s = store(); val original = s.current()!!
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            assertEquals("zima.test", chain.request().url.host)
            s.select(mac.value)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("{\"sessions\":[]}".toResponseBody("application/json".toMediaType())).build()
        }.build()
        val repo = AgentDeckRepository(s.credentials, backgroundScope, http, isSaved = s::isSaved)
        try { repo.callFor(original) { sessions() }; fail() } catch (_: ApiException.HostChanged) { }
    }
}
