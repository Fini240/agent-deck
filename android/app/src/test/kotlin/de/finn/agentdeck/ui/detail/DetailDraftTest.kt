package de.finn.agentdeck.ui.detail

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.finn.agentdeck.data.AgentDeckRepository
import de.finn.agentdeck.data.Credentials
import de.finn.agentdeck.data.DraftStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class DetailDraftTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun failedNotificationReplyAppearsInAnAlreadyOpenChatAndIsNotOverwritten() {
        val drafts = DraftStore(ctx.getSharedPreferences("detail-draft-test", Context.MODE_PRIVATE))
        val repo = AgentDeckRepository(MutableStateFlow<Credentials?>(null), CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))
        val vm = DetailViewModel("s1", repo, drafts)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("", vm.ui.value.draft)

        // App in background with this chat open; a reply from the shade fails and is kept as draft.
        drafts.restoreFailedReply("s1", "continue", "req-1")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("continue", vm.ui.value.draft)

        vm.setDraft(vm.ui.value.draft + " please")
        assertEquals("continue please", drafts.get("s1").text)
    }
}
