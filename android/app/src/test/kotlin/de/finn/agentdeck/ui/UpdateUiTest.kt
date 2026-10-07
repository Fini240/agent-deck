package de.finn.agentdeck.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.finn.agentdeck.core.api.ApkOffer
import de.finn.agentdeck.ui.settings.AppUpdateSection
import de.finn.agentdeck.ui.settings.SettingsActions
import de.finn.agentdeck.ui.theme.AgentDeckTheme
import de.finn.agentdeck.update.UpdatePhase
import de.finn.agentdeck.update.UpdateUi
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w390dp-h844dp")
class UpdateUiTest {
    @get:Rule val compose = createComposeRule()
    private val offer = ApkOffer(true, "0.2.2", 100, "a".repeat(64), "/apk")
    @Test fun readyUpdateRequiresExplicitInstallTap() {
        var taps = 0
        compose.setContent { AgentDeckTheme { AppUpdateSection(UpdateUi(UpdatePhase.READY, offer), "0.2.1", SettingsActions(onInstallUpdate = { taps++ })) } }
        assertEquals(0, taps); compose.onNodeWithText("Install update").performClick(); assertEquals(1, taps)
    }
    @Test fun progressDisablesChecksAndShowsMeasuredPercent() {
        compose.setContent { AgentDeckTheme { AppUpdateSection(UpdateUi(UpdatePhase.DOWNLOADING, offer, 50), "0.2.1", SettingsActions()) } }
        compose.onNodeWithText("Downloading 0.2.2: 50%").assertIsDisplayed()
        compose.onNodeWithText("Check for updates").assertIsNotEnabled()
    }
    @Test fun failedDownloadOffersRetryAndCheck() {
        var retries = 0
        compose.setContent { AgentDeckTheme { AppUpdateSection(UpdateUi(UpdatePhase.ERROR, offer, message = "Mac offline"), "0.2.1", SettingsActions(onDownloadUpdate = { retries++ })) } }
        compose.onNodeWithText("Mac offline").assertIsDisplayed(); compose.onNodeWithText("Retry download").performClick(); assertEquals(1, retries)
        compose.onNodeWithText("Check again").assertIsDisplayed()
    }
    @Test fun unpairedCannotCheck() {
        compose.setContent { AgentDeckTheme { AppUpdateSection(UpdateUi(), "0.2.1", SettingsActions(), paired = false) } }
        compose.onNodeWithText("Check for updates").assertIsNotEnabled()
    }
}
