package de.finn.agentdeck.update

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import de.finn.agentdeck.core.api.ApkOffer
import de.finn.agentdeck.core.api.ApkUpdateSource
import de.finn.agentdeck.core.api.ServerUrl
import de.finn.agentdeck.data.CredentialStore
import de.finn.agentdeck.data.SecretCipher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AppUpdatesTest {
    private val bytes = "verified".toByteArray()
    private val offer = ApkOffer(true, "9.0.0", bytes.size.toLong(), MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }, "/apk")
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private fun store(): CredentialStore {
        val prefs = context.getSharedPreferences("update-test", Context.MODE_PRIVATE).apply { edit().clear().commit() }
        return CredentialStore(prefs, object : SecretCipher {
            override fun encrypt(plain: ByteArray) = plain
            override fun decrypt(sealed: ByteArray) = sealed
        }).also { pair(it, "one") }
    }
    private fun pair(s: CredentialStore, id: String) = s.save(ServerUrl.trusted("https://$id.test"), "Mac", id, "test-token", java.util.Base64.getEncoder().encodeToString(ByteArray(32)))
    private inner class Source : ApkUpdateSource {
        var offers: ApkOffer? = offer
        var checks = 0; var downloads = 0
        var failure = false
        var gate: CompletableDeferred<Unit>? = null
        var stale = false
        override suspend fun check(): ApkOffer? {
            checks++
            if (stale) withContext(NonCancellable) { gate?.await() } else gate?.await()
            if (failure) throw IllegalArgumentException("Mac offline")
            return offers
        }
        override suspend fun download(offer: ApkOffer, destination: File, progress: (Long, Long) -> Unit) {
            downloads++; gate?.await()
            destination.writeBytes(bytes); progress(bytes.size.toLong(), bytes.size.toLong())
            if (failure) throw IllegalArgumentException("Download failed")
        }
    }
    @Test fun checksAreThrottledButExplicitRetryWorks() = runTest {
        val s = store(); val source = Source(); val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        try {
            val updates = AppUpdates(context, s, scope, { source }, {}, { 1000L }, io = StandardTestDispatcher(testScheduler))
            updates.check(); runCurrent(); assertEquals(UpdatePhase.AVAILABLE, updates.state.value.phase)
            updates.check(); runCurrent(); assertEquals(1, source.checks)
            updates.check(force = true); runCurrent(); assertEquals(2, source.checks)
        } finally { scope.cancel() }
    }
    @Test fun noOfferIsUnavailableAndNetworkErrorAllowsRetry() = runTest {
        val source = Source().apply { offers = null }; val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        try {
            val updates = AppUpdates(context, store(), scope, { source }, {}, io = StandardTestDispatcher(testScheduler))
            updates.check(); runCurrent(); assertEquals(UpdatePhase.UNAVAILABLE, updates.state.value.phase)
            source.failure = true; updates.check(true); runCurrent(); assertEquals(UpdatePhase.ERROR, updates.state.value.phase)
            assertEquals("Mac offline", updates.state.value.message)
            source.failure = false; source.offers = offer.copy(version = "0.1.0"); updates.check(true); runCurrent()
            assertEquals(UpdatePhase.CURRENT, updates.state.value.phase)
        } finally { scope.cancel() }
    }
    @Test fun lateCheckCannotPublishAfterPairingChange() = runTest {
        val s = store(); val gate = CompletableDeferred<Unit>(); val source = Source().apply { this.gate = gate; stale = true }
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        try {
            val updates = AppUpdates(context, s, scope, { source }, {}, io = StandardTestDispatcher(testScheduler))
            updates.check(); runCurrent(); pair(s, "two"); runCurrent(); gate.complete(Unit); runCurrent()
            assertEquals(UpdatePhase.IDLE, updates.state.value.phase)
        } finally { scope.cancel() }
    }
    @Test fun doubleDownloadAndForgetAreSafe() = runTest {
        val s = store(); val source = Source(); val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        try {
            val updates = AppUpdates(context, s, scope, { source }, {}, io = StandardTestDispatcher(testScheduler))
            updates.check(); runCurrent(); source.gate = CompletableDeferred()
            updates.download(); updates.download(); runCurrent(); assertEquals(1, source.downloads)
            s.clear(); runCurrent(); source.gate!!.complete(Unit); runCurrent()
            assertEquals(UpdatePhase.IDLE, updates.state.value.phase); assertNull(updates.installFile())
            assertTrue(File(context.cacheDir, "updates").listFiles().orEmpty().isEmpty())
        } finally { scope.cancel() }
    }
    @Test fun validationFailureRetainsOfferAndCleansDownload() = runTest {
        val source = Source(); val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val verified = CompletableDeferred<Unit>()
        try {
            val updates = AppUpdates(context, store(), scope, { source }, { verified.complete(Unit); throw IllegalArgumentException("Wrong signing key") }, io = StandardTestDispatcher(testScheduler))
            updates.check(); runCurrent(); updates.download(); runCurrent()
            verified.await()
            // Archive validation resumes from Dispatchers.IO back onto the test scheduler.
            withTimeout(5000) { updates.state.first { it.phase == UpdatePhase.ERROR } }
            assertEquals(UpdatePhase.ERROR, updates.state.value.phase); assertEquals(offer, updates.state.value.offer)
            assertTrue(File(context.cacheDir, "updates").listFiles().orEmpty().isEmpty())
        } finally { scope.cancel() }
    }
    @Test fun archiveIdentityRequiresNewerSameSignedApp() {
        val installed = ApkIdentity("de.finn.agentdeck", 4, setOf("key"))
        validateApkIdentity(installed, installed.copy(versionCode = 5))
        for (bad in listOf(installed, installed.copy(packageName = "other", versionCode = 5), installed.copy(signers = setOf("other"), versionCode = 5), installed.copy(signers = emptySet(), versionCode = 5))) {
            try { validateApkIdentity(installed, bad); fail("Expected rejection") } catch (_: IllegalArgumentException) {}
        }
    }
    @Test fun readyFileIsRevalidatedAndTamperingClearsIt() = runTest {
        val s = store(); val source = Source(); val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        try {
            val updates = AppUpdates(context, s, scope, { source }, {}, io = StandardTestDispatcher(testScheduler))
            updates.check(); runCurrent(); updates.download()
            withTimeout(5000) { updates.state.first { it.phase == UpdatePhase.READY } }
            val file = updates.installFile()!!
            assertArrayEquals(bytes, file.readBytes())
            file.writeBytes("modified".toByteArray())
            assertNull(updates.installFile()); assertFalse(file.exists())
            assertEquals(UpdatePhase.ERROR, updates.state.value.phase)
        } finally { scope.cancel() }
    }
    @Test fun explicitRecheckCanReplaceAReadyUpdate() = runTest {
        val source = Source(); val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        try {
            val updates = AppUpdates(context, store(), scope, { source }, {}, io = StandardTestDispatcher(testScheduler))
            updates.check(); runCurrent(); updates.download()
            withTimeout(5000) { updates.state.first { it.phase == UpdatePhase.READY } }
            updates.check(); runCurrent(); assertEquals(UpdatePhase.READY, updates.state.value.phase)
            source.offers = null; updates.check(force = true); runCurrent()
            assertEquals(UpdatePhase.UNAVAILABLE, updates.state.value.phase)
            assertNull(updates.installFile()); assertTrue(File(context.cacheDir, "updates").listFiles().orEmpty().isEmpty())
        } finally { scope.cancel() }
    }
}
