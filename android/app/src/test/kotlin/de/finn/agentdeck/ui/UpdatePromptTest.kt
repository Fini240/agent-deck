package de.finn.agentdeck.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.finn.agentdeck.core.api.ApkOffer
import de.finn.agentdeck.ui.settings.UpdatePrompt
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
class UpdatePromptTest {
    @get:Rule val compose = createComposeRule()
    private val offer = ApkOffer(true, "0.2.3", 100, "a".repeat(64), "/apk")
    @Test fun popupHasVisibleVersionAndExplicitUpdateAction() {
        var updates = 0
        compose.setContent { AgentDeckTheme { UpdatePrompt(UpdateUi(UpdatePhase.AVAILABLE, offer, showPrompt = true), { updates++ }, {}) } }
        compose.onNodeWithText("Agent Deck update available").assertIsDisplayed()
        compose.onNodeWithText("Version 0.2.3", substring = true).assertIsDisplayed()
        assertEquals(0, updates); compose.onNodeWithText("Update now").performClick(); assertEquals(1, updates)
    }
    @Test fun laterDoesNotStartUpdate() {
        var updates = 0; var dismissed = 0
        compose.setContent { AgentDeckTheme { UpdatePrompt(UpdateUi(UpdatePhase.AVAILABLE, offer), { updates++ }, { dismissed++ }) } }
        compose.onNodeWithText("Later").performClick(); assertEquals(1, dismissed); assertEquals(0, updates)
    }
    @Test fun readyPopupExplainsExistingDownload() {
        compose.setContent { AgentDeckTheme { UpdatePrompt(UpdateUi(UpdatePhase.READY, offer), {}, {}) } }
        compose.onNodeWithText("Version 0.2.3 is downloaded and ready to install.").assertIsDisplayed()
    }
}
