package de.finn.agentdeck.ui

import android.app.Application
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import de.finn.agentdeck.core.api.ServerUrl
import de.finn.agentdeck.data.*
import de.finn.agentdeck.ui.theme.AgentDeckTheme
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class)
class HostPickerTest {
    @get:Rule val compose = createComposeRule()
    private fun host(url: String, name: String) = SavedHost(url, name, name, "dev", 1, Credentials(ServerUrl.trusted(url), name, "dev", "token", ByteArray(32)))
    private val mac = host("https://mac.test", "Mac")
    private val zima = host("https://zima.test", "ZimaOS")
    private val hosts = listOf(mac, zima)
    private var selected = ""
    private var removed = ""
    private var add = false
    private fun render(width: String = "w390dp-h844dp", dark: Boolean = false, font: Float = 1f) {
        RuntimeEnvironment.setQualifiers("$width-${if(dark) "night" else "notnight"}-mdpi")
        RuntimeEnvironment.setFontScale(font)
        compose.setContent { AgentDeckTheme(darkTheme = dark) {
            Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                HostPicker(hosts, mac.key, { selected = it }, { add = true }, { _, _ -> }, { removed = it })
                Text("Mac is offline. You can switch to ZimaOS above.")
            }
        } }
    }
    private fun shot(name: String, dialog: Boolean = false) {
        val path = File(System.getProperty("user.dir"), "../../outputs/screenshots/android/$name.png").canonicalFile
        (if(dialog) compose.onNode(isDialog()) else compose.onRoot()).captureRoboImage(path.path)
    }
    @Test fun offlineSelectedHostStillLetsYouSwitch() {
        render(); compose.onNodeWithText("Mac ▾").performClick()
        compose.onNodeWithText("ZimaOS").performClick(); assertEquals(zima.key, selected)
    }
    @Test fun addDeviceRemainsReachableFromHome() {
        render(); compose.onNodeWithText("Add device").performClick(); assertTrue(add)
    }
    @Test fun removeRequiresExplicitChoiceAndCanBeCancelled() {
        render(); compose.onNodeWithText("Mac ▾").performClick()
        compose.onAllNodesWithText("Remove")[0].performClick()
        assertEquals("", removed); compose.onNodeWithText("Cancel").performClick(); assertEquals("", removed)
    }
    @Test fun coverLightScreenshot() { render(); shot("devices_cover390_light") }
    @Test fun innerDarkScreenshot() { render("w768dp-h900dp", true); shot("devices_inner768_dark") }
    @Test fun wideLightScreenshot() { render("w1440dp-h900dp"); shot("devices_wide1440_light") }
    @Test fun pickerCoverLargeFontScreenshot() { render(dark = true, font = 2f); compose.onNodeWithText("Mac ▾").performClick(); shot("devices_picker_cover390_large_dark", true) }
}
