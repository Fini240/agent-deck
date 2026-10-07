package de.finn.agentdeck.update

import android.app.Application
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class UpdateInstallerTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    @Test fun installerGetsReadOnlyContentUriForNarrowUpdateDirectory() {
        val file = File(context.cacheDir, "updates/ready.apk").apply { parentFile!!.mkdirs(); writeText("fixture") }
        try {
            val intent = UpdateInstaller.installIntent(context, file)
            assertEquals(Intent.ACTION_VIEW, intent.action)
            assertEquals("content", intent.data!!.scheme)
            assertEquals("application/vnd.android.package-archive", intent.type)
            assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, intent.flags)
            assertEquals(intent.data, intent.clipData!!.getItemAt(0).uri)
            val provider = context.packageManager.resolveContentProvider("${context.packageName}.updates", 0)!!
            assertFalse(provider.exported); assertTrue(provider.grantUriPermissions)
            assertEquals("fixture", context.contentResolver.openInputStream(intent.data!!)!!.bufferedReader().use { it.readText() })
        } finally { file.delete() }
    }
    @Test fun fileProviderDoesNotExposeOtherCacheFiles() {
        val privateFile = File(context.cacheDir, "private.txt").apply { writeText("private fixture") }
        try {
            try { UpdateInstaller.installIntent(context, privateFile); fail("Other cache files must not be exposed") } catch (_: IllegalArgumentException) {}
        } finally { privateFile.delete() }
    }
    @Test fun permissionScreenTargetsOnlyThisApp() {
        val intent = UpdateInstaller.permissionIntent(context)
        assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, intent.action)
        assertEquals("package:${context.packageName}", intent.data.toString())
    }
}
