package de.finn.agentdeck.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.finn.agentdeck.core.api.ApiException
import de.finn.agentdeck.core.model.TerminalKey
import de.finn.agentdeck.data.AgentDeckRepository
import de.finn.agentdeck.data.DraftStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.UUID

class DetailViewModel(
    private val sessionId: String,
    private val repo: AgentDeckRepository,
    private val drafts: DraftStore,
) : ViewModel() {
    private val _ui = MutableStateFlow(DetailUi(draft = drafts.get(sessionId).text))
    val ui: StateFlow<DetailUi> = _ui.asStateFlow()
    private var terminalJob: Job? = null
    /** Session actually controlled; may change after resume (managed id replaces discovered id). */
    private var targetId = sessionId

    init {
        viewModelScope.launch {
            repo.sessions.collect { st ->
                val s = st.sessions.firstOrNull { it.id == targetId }
                val parent = s?.parentSessionId?.let { p -> st.sessions.firstOrNull { it.id == p } }
                _ui.update { it.copy(session = s ?: it.session, parent = parent ?: it.parent, now = Instant.now()) }
            }
        }
        viewModelScope.launch {
            repo.events.collect { e ->
                when {
                    e.type == "poll" || e.type == "reconnected" -> refresh()
                    e.sessionId == targetId && e.type == "messages" -> loadMessages()
                    e.sessionId == targetId && (e.type == "approval" || e.type == "notification") -> { loadApprovals(); loadMessages() }
                }
            }
        }
        viewModelScope.launch {
            // A notification reply that failed while this chat stayed open: show it instead of
            // letting the next keystroke overwrite it.
            drafts.externalChanges.collect { id -> if (id == sessionId) _ui.update { it.copy(draft = drafts.get(sessionId).text) } }
        }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch { loadMessages() }
        viewModelScope.launch { loadApprovals() }
        if (_ui.value.tab == DetailTab.TERMINAL) viewModelScope.launch { loadTerminal() }
    }

    private suspend fun loadMessages() {
        try {
            val m = repo.call { messages(targetId, 100) }.messages
            _ui.update { it.copy(messages = m, messagesLoaded = true, messagesError = null) }
        } catch (e: ApiException) {
            _ui.update { it.copy(messagesLoaded = true, messagesError = e.message) }
        }
    }

    private suspend fun loadApprovals() {
        val s = _ui.value.session
        if (s != null && (!s.managed || s.isSubagent)) return
        try {
            val a = repo.call { approvals(targetId) }.approvals
            _ui.update { it.copy(approvals = a) }
        } catch (_: ApiException) {
            // Approvals are best-effort here; the chat stays usable and the next refresh retries.
        }
    }

    private suspend fun loadTerminal() {
        try {
            val t = repo.call { terminal(targetId) }
            _ui.update { it.copy(terminalText = t.text, terminalAvailable = t.available, terminalLoaded = true) }
        } catch (e: ApiException) {
            _ui.update { it.copy(terminalLoaded = true, actionError = e.message) }
        }
    }

    fun setTab(tab: DetailTab) {
        _ui.update { it.copy(tab = tab) }
        terminalJob?.cancel()
        if (tab == DetailTab.TERMINAL) {
            // Bounded polling while the terminal tab is visible only.
            terminalJob = viewModelScope.launch {
                while (isActive) {
                    loadTerminal()
                    delay(3_000)
                }
            }
        }
    }

    fun setDraft(text: String) {
        drafts.setText(sessionId, text)
        _ui.update { it.copy(draft = text, sendError = null) }
    }

    /** Always interrupt = true per contract; the requestId is reused for retries of the same text. */
    fun send() {
        val sentDraft = _ui.value.draft
        val text = sentDraft.trim()
        if (text.isEmpty() || _ui.value.sending) return
        val requestId = drafts.requestIdFor(sessionId, sentDraft)
        _ui.update { it.copy(sending = true, sendError = null) }
        viewModelScope.launch {
            try {
                repo.call { send(targetId, text, requestId) }
                drafts.clearIfSent(sessionId, sentDraft)
                _ui.update { it.copy(sending = false, draft = drafts.get(sessionId).text, info = null) }
                loadMessages()
            } catch (e: ApiException) {
                _ui.update { it.copy(sending = false, sendError = e.message) }
            }
        }
    }

    fun stop() = action("stop") {
        repo.call { stop(targetId, UUID.randomUUID().toString()) }
        _ui.update { it.copy(info = "Stop sent. The conversation is kept.") }
    }

    fun resume() = action("resume") {
        val s = repo.call { resume(targetId, UUID.randomUUID().toString()) }.session
        targetId = s.id
        _ui.update { it.copy(session = s, info = "Resumed in a managed terminal on the Mac. You can reply now.") }
        repo.refreshSessions()
        refresh()
    }

    fun approve(approvalId: String, choiceId: String) {
        if (_ui.value.approvalBusy != null) return
        _ui.update { it.copy(approvalBusy = approvalId) }
        viewModelScope.launch {
            try {
                repo.call { respondApproval(targetId, approvalId, choiceId, UUID.randomUUID().toString()) }
            } catch (e: ApiException) {
                val msg = if (e is ApiException.Http && e.status == 409) "That prompt changed on the Mac. Showing the current one." else e.message
                _ui.update { it.copy(actionError = msg) }
            } finally {
                _ui.update { it.copy(approvalBusy = null) }
                loadApprovals()
            }
        }
    }

    fun key(k: TerminalKey) = action("key") {
        repo.call { inputKey(targetId, k) }
        delay(300)
        loadTerminal()
    }

    fun dismissMessage() = _ui.update { it.copy(actionError = null, info = null) }

    private fun action(name: String, block: suspend () -> Unit) {
        if (_ui.value.busyAction != null) return
        _ui.update { it.copy(busyAction = name, actionError = null) }
        viewModelScope.launch {
            try {
                block()
            } catch (e: ApiException) {
                _ui.update { it.copy(actionError = e.message) }
            } finally {
                _ui.update { it.copy(busyAction = null) }
            }
        }
    }
}
