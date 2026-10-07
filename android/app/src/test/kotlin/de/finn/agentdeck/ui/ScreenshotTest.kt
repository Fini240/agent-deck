package de.finn.agentdeck.ui

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import de.finn.agentdeck.core.model.ActivityFilter
import de.finn.agentdeck.core.model.BrowserScope
import de.finn.agentdeck.data.Connection
import de.finn.agentdeck.push.PushStatus
import de.finn.agentdeck.ui.detail.DetailActions
import de.finn.agentdeck.ui.detail.DetailTab
import de.finn.agentdeck.ui.detail.DetailUi
import de.finn.agentdeck.ui.detail.SessionDetailPane
import de.finn.agentdeck.ui.newsession.ModelChoice
import de.finn.agentdeck.ui.newsession.NewSessionActions
import de.finn.agentdeck.ui.newsession.NewSessionScreen
import de.finn.agentdeck.ui.newsession.NewSessionUi
import de.finn.agentdeck.ui.pairing.PairingActions
import de.finn.agentdeck.ui.pairing.PairingScreen
import de.finn.agentdeck.ui.pairing.PairingUi
import de.finn.agentdeck.ui.sessions.SessionListPane
import de.finn.agentdeck.ui.sessions.SessionListUi
import de.finn.agentdeck.ui.settings.SettingsActions
import de.finn.agentdeck.ui.settings.SettingsScreen
import de.finn.agentdeck.ui.settings.SettingsUi
import de.finn.agentdeck.ui.theme.AgentDeckTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders the real composables with fixture state at Fold cover (~390 dp), Fold inner (768 dp)
 * and wide (1440 dp) widths, light and dark. PNGs go to <repo>/outputs/screenshots/android/
 * when run with `-Proborazzi.test.record=true` (or `recordRoborazziDebug`).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class)
class ScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val outDir = File(System.getProperty("user.dir"), "../../outputs/screenshots/android").canonicalFile

    private fun shot(name: String, width: Width, dark: Boolean, fontScale: Float = 1f, dialog: Boolean = false, content: @Composable () -> Unit) {
        RuntimeEnvironment.setQualifiers("${width.size}-${if (dark) "night" else "notnight"}-${width.dpi}")
        RuntimeEnvironment.setFontScale(fontScale)
        compose.setContent {
            AgentDeckTheme(darkTheme = dark) {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { content() }
                }
            }
        }
        (if (dialog) compose.onNode(isDialog()) else compose.onRoot()).captureRoboImage(File(outDir, "${name}_${width.label}_${if (dark) "dark" else "light"}.png").path)
    }

    @Test fun updateAvailableCover() = shot("update_available", Width.COVER, false) {
        SettingsScreen(SettingsUi(appVersion = "0.2.1", serverUrl = "https://example.test", update = de.finn.agentdeck.update.UpdateUi(de.finn.agentdeck.update.UpdatePhase.AVAILABLE, de.finn.agentdeck.core.api.ApkOffer(true, "0.2.2", 15000000, "a".repeat(64), "/apk"))), SettingsActions())
    }
    @Test fun updateProgressInnerDark() = shot("update_progress", Width.INNER, true) {
        SettingsScreen(SettingsUi(appVersion = "0.2.1", serverUrl = "https://example.test", update = de.finn.agentdeck.update.UpdateUi(de.finn.agentdeck.update.UpdatePhase.DOWNLOADING, de.finn.agentdeck.core.api.ApkOffer(true, "0.2.2", 15000000, "a".repeat(64), "/apk"), 7500000)), SettingsActions())
    }
    @Test fun updateReadyWide() = shot("update_ready", Width.WIDE, false) {
        SettingsScreen(SettingsUi(appVersion = "0.2.1", serverUrl = "https://example.test", update = de.finn.agentdeck.update.UpdateUi(de.finn.agentdeck.update.UpdatePhase.READY, de.finn.agentdeck.core.api.ApkOffer(true, "0.2.2", 15000000, "a".repeat(64), "/apk"))), SettingsActions())
    }
    @Test fun updateErrorCoverLargeText() = shot("update_error", Width.COVER, true, fontScale = 2f) {
        SettingsScreen(SettingsUi(appVersion = "0.2.1", serverUrl = "https://example.test", update = de.finn.agentdeck.update.UpdateUi(de.finn.agentdeck.update.UpdatePhase.ERROR, message = "Could not reach your Mac. Keep Tailscale connected and retry.")), SettingsActions())
    }

    private val promptOffer = de.finn.agentdeck.core.api.ApkOffer(true, "0.2.3", 15000000, "a".repeat(64), "/apk")
    @Test fun updatePopupCover() = shot("update_popup", Width.COVER, false, dialog = true) {
        de.finn.agentdeck.ui.settings.UpdatePrompt(de.finn.agentdeck.update.UpdateUi(de.finn.agentdeck.update.UpdatePhase.AVAILABLE, promptOffer), {}, {})
    }
    @Test fun updatePopupInnerDark() = shot("update_popup", Width.INNER, true, dialog = true) {
        de.finn.agentdeck.ui.settings.UpdatePrompt(de.finn.agentdeck.update.UpdateUi(de.finn.agentdeck.update.UpdatePhase.AVAILABLE, promptOffer), {}, {})
    }
    @Test fun updatePopupWide() = shot("update_popup", Width.WIDE, false, dialog = true) {
        de.finn.agentdeck.ui.settings.UpdatePrompt(de.finn.agentdeck.update.UpdateUi(de.finn.agentdeck.update.UpdatePhase.READY, promptOffer), {}, {})
    }
    @Test fun updatePopupLargeText() = shot("update_popup_large", Width.COVER, true, fontScale = 2f, dialog = true) {
        de.finn.agentdeck.ui.settings.UpdatePrompt(de.finn.agentdeck.update.UpdateUi(de.finn.agentdeck.update.UpdatePhase.AVAILABLE, promptOffer), {}, {})
    }

    enum class Width(val label: String, val size: String, val dpi: String) {
        COVER("cover390", "w390dp-h844dp", "xhdpi"),
        INNER("inner768", "w768dp-h900dp", "hdpi"),
        WIDE("wide1440", "w1440dp-h900dp", "mdpi"),
        SMALL("small375", "w375dp-h812dp", "xhdpi"),
        LANDSCAPE("landscape844", "w844dp-h390dp", "mdpi"),
    }

    private val expandedAll = setOf(Fixtures.claudeParent.id, "codex:01a10000-0000-7000-8000-000000000001")

    private fun listUi(selected: String? = null, activity: ActivityFilter = ActivityFilter.ALL) = SessionListUi(
        sessions = Fixtures.sessions, connection = Connection.Live, loaded = true, serverName = "example-mac",
        expanded = expandedAll, selectedId = selected, activity = activity, now = Fixtures.now,
    )

    @Composable
    private fun List(ui: SessionListUi) = SessionListPane(ui, {}, {}, {}, {}, {}, {}, {}, {})

    private fun childDetail() = DetailUi(
        session = Fixtures.workingChild, parent = Fixtures.claudeParent, messages = Fixtures.childMessages, messagesLoaded = true, now = Fixtures.now,
    )

    private fun parentDetail() = DetailUi(
        session = Fixtures.claudeParent, messages = Fixtures.parentMessages, messagesLoaded = true,
        draft = "Also run the lint task before you finish.", now = Fixtures.now,
    )

    @Composable
    private fun Home(selectedDetail: DetailUi?, selected: String?) {
        BoxWithConstraints {
            val wide = maxWidth >= TwoPaneMinWidth
            AdaptiveHome(
                wide = wide, totalWidth = maxWidth, showDetail = selectedDetail != null,
                list = { List(listUi(selected)) },
                detail = { selectedDetail?.let { SessionDetailPane(it, DetailActions(onBack = if (wide) null else ({}))) } },
            )
        }
    }

    private fun richDetail() = parentDetail().copy(draft = "", messages = listOf(
        de.finn.agentdeck.core.model.Message("formatted", "assistant", "## Build progress\n\n- **Android:** tests passed\n- **Mac helper:** unchanged\n\nRun `ad` to pick your chat.\n\n```shell\nad pair\n```", "2026-10-07T09:11:50Z")))
    @Test fun needsYouCover() = shot("needs_you", Width.COVER, false) {
        List(listUi(activity = ActivityFilter.ATTENTION).copy(expanded = emptySet(), collapsedFolders = Fixtures.sessions.map { it.projectKey }.toSet()))
    }
    @Test fun pinnedInnerDark() = shot("pinned", Width.INNER, true) {
        List(listUi().copy(pinned = setOf(Fixtures.claudeParent.id), pinnedOnly = true))
    }
    @Test fun formattedCover() = shot("formatted_chat", Width.COVER, false) { SessionDetailPane(richDetail(), DetailActions(onBack = {})) }
    @Test fun formattedLargeText() = shot("formatted_large_text", Width.SMALL, true, fontScale = 2f) { SessionDetailPane(richDetail(), DetailActions(onBack = {})) }
    @Test fun formattedWide() = shot("formatted_chat", Width.WIDE, false) {
        AdaptiveHome(wide = true, totalWidth = androidx.compose.ui.unit.Dp(1440f), showDetail = true,
            list = { List(listUi()) }, detail = { SessionDetailPane(richDetail(), DetailActions()) })
    }

    // ---- Grouped list -------------------------------------------------------------------
    @Test fun listCoverLight() = shot("list_grouped", Width.COVER, false) { Home(null, null) }
    @Test fun listCoverDark() = shot("list_grouped", Width.COVER, true) { Home(null, null) }
    @Test fun listActiveFilterCover() = shot("list_active", Width.COVER, false) { List(listUi(activity = ActivityFilter.ACTIVE)) }
    @Test fun listAllChatsCover() = shot("list_all", Width.COVER, false) { List(listUi().copy(scope = BrowserScope.ALL)) }
    @Test fun listSearchCover() = shot("list_search", Width.COVER, false) { List(listUi().copy(query = "model docs", expanded = emptySet())) }
    @Test fun listSmallDark() = shot("list_small", Width.SMALL, true) { List(listUi()) }
    @Test fun listLargeTextCover() = shot("list_large_text", Width.COVER, false, fontScale = 2f) { List(listUi()) }
    @Test fun chatLargeTextCover() = shot("chat_large_text", Width.COVER, true, fontScale = 2f) { Home(parentDetail(), Fixtures.claudeParent.id) }
    @Test fun homeLandscape() = shot("home", Width.LANDSCAPE, false) { Home(parentDetail(), Fixtures.claudeParent.id) }

    // ---- Child agent detail (read-only) ---------------------------------------------------
    @Test fun childDetailCoverLight() = shot("child_detail", Width.COVER, false) { Home(childDetail(), Fixtures.workingChild.id) }
    @Test fun childDetailCoverDark() = shot("child_detail", Width.COVER, true) { Home(childDetail(), Fixtures.workingChild.id) }
    @Test fun childDetailInnerLight() = shot("two_pane_child", Width.INNER, false) { Home(childDetail(), Fixtures.workingChild.id) }
    @Test fun childDetailInnerDark() = shot("two_pane_child", Width.INNER, true) { Home(childDetail(), Fixtures.workingChild.id) }
    @Test fun childDetailWideLight() = shot("two_pane_child", Width.WIDE, false) { Home(childDetail(), Fixtures.workingChild.id) }
    @Test fun childDetailWideDark() = shot("two_pane_child", Width.WIDE, true) { Home(childDetail(), Fixtures.workingChild.id) }

    // ---- Parent chat with composer / approvals / terminal ----------------------------------
    @Test fun parentDetailCover() = shot("parent_chat", Width.COVER, false) { Home(parentDetail(), Fixtures.claudeParent.id) }
    @Test fun parentDetailInner() = shot("parent_chat", Width.INNER, false) { Home(parentDetail(), Fixtures.claudeParent.id) }
    @Test fun approvalCoverDark() = shot("approval", Width.COVER, true) {
        Home(DetailUi(session = Fixtures.needsInput, approvals = listOf(Fixtures.approval), messages = Fixtures.parentMessages.take(2), messagesLoaded = true, now = Fixtures.now), Fixtures.needsInput.id)
    }
    @Test fun sendErrorCover() = shot("send_error", Width.COVER, false) {
        Home(parentDetail().copy(sendError = "Can't reach the Mac. Check that Tailscale is connected on this phone and the helper is running."), Fixtures.claudeParent.id)
    }
    @Test fun terminalCover() = shot("terminal", Width.COVER, false) {
        Home(
            parentDetail().copy(tab = DetailTab.TERMINAL, terminalLoaded = true, terminalAvailable = true, terminalText = "╭─ Claude Code ─╮\n> Running gradle lint…\n  ✓ 0 errors, 2 warnings\n\n? Allow Bash(npm test)  1. Yes  2. No"),
            Fixtures.claudeParent.id,
        )
    }
    @Test fun resumeReadOnlyCover() = shot("resume_readonly", Width.COVER, false) {
        val codex = Fixtures.sessions.first { it.id == "codex:01a10000-0000-7000-8000-000000000001" }
        Home(DetailUi(session = codex, messages = Fixtures.parentMessages.take(2), messagesLoaded = true, now = Fixtures.now), codex.id)
    }

    // ---- Other screens ------------------------------------------------------------------
    @Test fun newSessionCover() = shot("new_session", Width.COVER, false) {
        NewSessionScreen(
            NewSessionUi(agents = Fixtures.models, choice = ModelChoice.Custom, customModel = "claude-opus-5-5", workspaces = Fixtures.serverSettings.allowedWorkspaces, cwd = "/Users/finn/Documents/Codex", modelsRefreshedAt = "2026-10-07T09:10:00Z"),
            NewSessionActions(),
        )
    }
    @Test fun newSessionInnerDark() = shot("new_session", Width.INNER, true) {
        NewSessionScreen(NewSessionUi(agents = Fixtures.models, choice = ModelChoice.Listed("sonnet"), cwd = "/Users/finn/Developer", workspaces = Fixtures.serverSettings.allowedWorkspaces), NewSessionActions())
    }
    @Test fun settingsCoverUnconfigured() = shot("settings_push_unconfigured", Width.COVER, false) {
        SettingsScreen(SettingsUi(serverName = "example-mac", serverUrl = "https://mac.example.ts.net:8443", deviceId = "dev_3f9a1c2b7e", connection = Connection.Live, push = PushStatus(buildConfigured = false), appVersion = "0.1.0"), SettingsActions())
    }
    @Test fun settingsCoverRegisteredDark() = shot("settings_push_registered", Width.COVER, true) {
        SettingsScreen(
            SettingsUi(
                serverName = "example-mac", serverUrl = "https://mac.example.ts.net:8443", deviceId = "dev_3f9a1c2b7e",
                connection = Connection.Live, serverStatus = Fixtures.serverStatus, serverSettings = Fixtures.serverSettings, appVersion = "0.1.0",
                push = PushStatus(true, token = PushStatus.TokenState.Available, registration = PushStatus.Registration.Registered(0)),
            ),
            SettingsActions(),
        )
    }
    @Test fun pairingCover() = shot("pairing", Width.COVER, false) {
        PairingScreen(PairingUi(server = "mac.example.ts.net:8443", deviceName = "Samsung SM-F966B", error = "Only HTTPS servers are allowed. Use the https:// address shown by the Mac helper."), PairingActions())
    }
    @Test fun offlineListCover() = shot("list_polling", Width.COVER, false) {
        List(listUi().copy(connection = Connection.Polling("Can't reach the Mac. Check that Tailscale is connected on this phone and the helper is running.", 10)))
    }
}
