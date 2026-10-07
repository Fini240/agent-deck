package de.finn.agentdeck.ui.detail

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.finn.agentdeck.core.api.ApiException
import de.finn.agentdeck.core.api.ServerUrl
import de.finn.agentdeck.data.AgentDeckRepository
import de.finn.agentdeck.data.Credentials
import de.finn.agentdeck.data.DraftStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class PairingAndDraftRaceTest {
    private fun creds(host: String, id: String) = Credentials(ServerUrl.trusted("https://$host"), "Mac", id, "token-$id", ByteArray(32))
    private fun response(chain: okhttp3.Interceptor.Chain, json: String) = Response.Builder()
        .request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
        .body(json.toResponseBody("application/json".toMediaType())).build()

    @Test
    fun typingNextDraftWhileSendWaitsNeverClearsIt() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val path = chain.request().url.encodedPath
            if (path.endsWith("/send")) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                response(chain, "{\"accepted\":true}")
            } else response(chain, if (path.endsWith("/messages")) "{\"messages\":[]}" else "{\"approvals\":[]}")
        }.build()
        val identity = MutableStateFlow<Credentials?>(creds("mac-a.ts.net", "dev-a"))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val repo = AgentDeckRepository(identity, scope, http)
        val context: Context = ApplicationProvider.getApplicationContext()
        val drafts = DraftStore(context.getSharedPreferences("next-draft-race", Context.MODE_PRIVATE))
        val vm = DetailViewModel("s1", repo, drafts)
        vm.setDraft("first message")
        vm.send()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        vm.setDraft("second message")
        release.countDown()
        val end = System.currentTimeMillis() + 5000
        while (vm.ui.value.sending && System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertFalse(vm.ui.value.sending)
        assertEquals("second message", drafts.get("s1").text)
        assertEquals("second message", vm.ui.value.draft)
    }

    @Test
    fun retainedClientCannotSendNewPairingsTokenToOldServer() = runBlocking {
        val hosts = mutableListOf<String>()
        val tokens = mutableListOf<String?>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            hosts.add(chain.request().url.host)
            tokens.add(chain.request().header("Authorization"))
            response(chain, "{\"sessions\":[]}")
        }.build()
        val identities = MutableStateFlow<Credentials?>(creds("mac-a.ts.net", "dev-a"))
        val repo = AgentDeckRepository(identities, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate), http)
        val old = repo.client()
        val oldIdentity = identities.value!!
        identities.value = creds("mac-b.ts.net", "dev-b")
        try { old.sessions(); fail("stale client used") } catch (_: ApiException.NotPaired) { }
        try { repo.callFor(oldIdentity) { sessions() }; fail("stale work used") } catch (_: ApiException.NotPaired) { }
        repo.call { sessions() }
        assertEquals(listOf("mac-b.ts.net"), hosts)
        assertEquals(listOf("Bearer token-dev-b"), tokens)
    }
}
