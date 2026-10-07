package de.finn.agentdeck.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import de.finn.agentdeck.BuildConfig
import de.finn.agentdeck.core.api.ApkOffer
import de.finn.agentdeck.core.api.ApkUpdateClient
import de.finn.agentdeck.core.api.ApkUpdateSource
import de.finn.agentdeck.core.api.AppVersion
import de.finn.agentdeck.data.CredentialStore
import de.finn.agentdeck.data.Credentials
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.coroutineContext

enum class UpdatePhase { IDLE, CHECKING, CURRENT, UNAVAILABLE, AVAILABLE, DOWNLOADING, READY, ERROR }
data class UpdateUi(
    val phase: UpdatePhase = UpdatePhase.IDLE,
    val offer: ApkOffer? = null,
    val downloaded: Long = 0,
    val message: String? = null,
) {
    val busy get() = phase == UpdatePhase.CHECKING || phase == UpdatePhase.DOWNLOADING
    val updateAvailable get() = phase in setOf(UpdatePhase.AVAILABLE, UpdatePhase.DOWNLOADING, UpdatePhase.READY) || (phase == UpdatePhase.ERROR && offer != null)
}

/** App-scoped: survives rotation and leaving Settings; no secret is sent to download routes. */
class AppUpdates(
    private val context: Context,
    private val credentials: CredentialStore,
    private val scope: CoroutineScope,
    private val client: (Credentials) -> ApkUpdateSource = { ApkUpdateClient(it.server) },
    private val verifyArchive: (File) -> Unit = { verifyApk(context, it) },
    private val now: () -> Long = { android.os.SystemClock.elapsedRealtime() },
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val _state = MutableStateFlow(UpdateUi())
    val state = _state.asStateFlow()
    private var identity = credentials.current()
    private var job: Job? = null
    private var ready: File? = null
    private var lastCheck: Long? = null
    private val directory = File(context.cacheDir, "updates").apply { mkdirs() }

    init {
        // A previous process's partial/ready files are not treated as validated state.
        directory.listFiles()?.forEach { it.delete() }
        scope.launch {
            credentials.credentials.collect { syncIdentity() }
        }
    }

    private fun syncIdentity() {
        val current = credentials.current()
        if (current != identity) {
            identity = current
            job?.cancel()
            ready?.delete(); ready = null
            lastCheck = null
            _state.value = UpdateUi()
        }
    }

    private fun valid(expected: Credentials) = credentials.current() == expected && identity == expected

    fun check(force: Boolean = false) {
        syncIdentity()
        val expected = identity ?: return
        if (_state.value.busy || (!force && _state.value.phase == UpdatePhase.READY)) return
        if (!force && lastCheck?.let { now() - it < 60 * 60 * 1000L } == true) return
        lastCheck = now()
        ready?.delete(); ready = null
        _state.value = UpdateUi(UpdatePhase.CHECKING)
        job = scope.launch {
            try {
                val offer = client(expected).check()
                if (!valid(expected)) return@launch
                val phase = when {
                    offer == null -> UpdatePhase.UNAVAILABLE
                    AppVersion.parse(offer.version)!! > AppVersion.parse(BuildConfig.VERSION_NAME)!! -> UpdatePhase.AVAILABLE
                    else -> UpdatePhase.CURRENT
                }
                _state.value = UpdateUi(phase, offer.takeIf { phase == UpdatePhase.AVAILABLE })
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (valid(expected)) _state.value = UpdateUi(UpdatePhase.ERROR, message = e.message ?: "Could not check for updates. Keep Tailscale connected and retry.")
            }
        }
    }

    fun download() {
        syncIdentity()
        val expected = identity ?: return
        val offer = _state.value.offer ?: return
        if (_state.value.busy || ready != null) return
        _state.value = UpdateUi(UpdatePhase.DOWNLOADING, offer)
        job = scope.launch {
            val partial = File(directory, "${UUID.randomUUID()}.part")
            var artifact: File? = null
            try {
                client(expected).download(offer, partial) { n, _ ->
                    if (valid(expected)) _state.update { it.copy(downloaded = n) }
                }
                withContext(io) { verifyArchive(partial) }
                coroutineContext.ensureActive()
                if (!valid(expected)) return@launch
                artifact = File(directory, "${UUID.randomUUID()}.apk")
                require(partial.renameTo(artifact)) { "Could not save the update. Retry the download." }
                ready = artifact
                _state.value = UpdateUi(UpdatePhase.READY, offer, offer.size!!)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (valid(expected)) _state.value = UpdateUi(UpdatePhase.ERROR, offer, message = e.message ?: "The update could not be downloaded. Retry.")
            } finally {
                partial.delete()
                if (!valid(expected)) artifact?.delete()
            }
        }
    }

    /** Recheck the cached APK before granting the Android installer read access. */
    suspend fun installFile(): File? {
        syncIdentity()
        val expected = identity ?: return null
        val file = ready ?: return null
        val offer = _state.value.offer ?: return null
        try {
            withContext(io) {
                require(file.length() == offer.size) { "The downloaded update is missing. Download it again." }
                val h = MessageDigest.getInstance("SHA-256")
                file.inputStream().use { input ->
                    val b = ByteArray(64 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(b); if (n < 0) break
                        h.update(b, 0, n)
                    }
                }
                require(h.digest().joinToString("") { "%02x".format(it) }.equals(offer.sha256, true)) { "The cached update changed. Download it again." }
                verifyArchive(file)
            }
            return file.takeIf { valid(expected) && ready == file }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (valid(expected) && ready == file) {
                ready = null; file.delete()
                _state.value = UpdateUi(UpdatePhase.ERROR, offer, message = e.message ?: "Download the update again.")
            }
            return null
        }
    }

    fun installerMessage(message: String) {
        syncIdentity()
        if (ready != null) _state.update { it.copy(message = message) }
    }
}

data class ApkIdentity(val packageName: String, val versionCode: Long, val signers: Set<String>)

fun validateApkIdentity(installed: ApkIdentity, candidate: ApkIdentity) {
    require(candidate.packageName == installed.packageName) { "This update is for a different app." }
    require(candidate.versionCode > installed.versionCode) { "This APK is not newer than the installed app." }
    require(installed.signers.isNotEmpty() && candidate.signers == installed.signers) { "This update uses a different signing key." }
}

@Suppress("DEPRECATION")
fun verifyApk(context: Context, file: File) {
    val pm = context.packageManager
    val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
    val installed = pm.getPackageInfo(context.packageName, flags)
    val candidate = pm.getPackageArchiveInfo(file.absolutePath, flags)
        ?: throw IllegalArgumentException("The downloaded file is not a readable Android APK.")
    fun identity(info: PackageInfo): ApkIdentity {
        val signers = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        return ApkIdentity(info.packageName, if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong(),
            signers.orEmpty().map { s -> MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) } }.toSet())
    }
    validateApkIdentity(identity(installed), identity(candidate))
}
