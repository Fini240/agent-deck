package de.finn.agentdeck.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionBrowserTest {
    private val fixture: List<Session> = AgentDeckJson.decodeFromString(
        SessionsResponse.serializer(),
        javaClass.getResource("/sessions-with-subagents.json")!!.readText(),
    ).sessions

    private val claudeParent = "claude:11111111-2222-4333-8444-555555555555"
    private val codexParent = "codex:01a10000-0000-7000-8000-000000000001"

    private fun s(
        id: String,
        status: SessionStatus = SessionStatus.IDLE,
        parent: String? = null,
        agent: String = "claude",
        cwd: String = "/p",
        title: String = id,
        live: Boolean? = null,
        send: Boolean = false,
        managed: Boolean = false,
        name: String? = null,
        task: String? = null,
    ) = Session(
        id = id, agent = agent, cwd = cwd, title = title, status = status, parentSessionId = parent, isSubagent = parent != null,
        live = live, managed = managed, capabilities = Capabilities(send = send), agentName = name, task = task,
        updatedAt = "2026-10-07T00:00:00Z",
    )

    private fun BrowserResult.ids(): List<String> = rows.mapNotNull {
        when (it) {
            is ChatRow -> it.session.id
            is AgentRow -> it.session.id
            is FolderRow -> null
        }
    }

    private fun BrowserResult.chat(id: String) = rows.filterIsInstance<ChatRow>().single { it.session.id == id }
    private fun BrowserResult.agent(id: String) = rows.filterIsInstance<AgentRow>().single { it.session.id == id }

    @Test
    fun openIncludesIdleLiveUnknownLiveAndSendableButNotHistory() {
        assertTrue(SessionBrowser.isOpen(s("a", SessionStatus.IDLE, live = true)))
        assertTrue(SessionBrowser.isOpen(s("b", SessionStatus.UNKNOWN, live = true)))
        assertTrue(SessionBrowser.isOpen(s("c", SessionStatus.WORKING, live = false)))
        assertTrue(SessionBrowser.isOpen(s("d", SessionStatus.NEEDS_INPUT)))
        assertTrue(SessionBrowser.isOpen(s("e", SessionStatus.IDLE, managed = true, send = true)))
        assertFalse(SessionBrowser.isOpen(s("f", SessionStatus.OFFLINE, live = false)))
        assertFalse(SessionBrowser.isOpen(s("g", SessionStatus.IDLE, live = false)))
        assertFalse(SessionBrowser.isOpen(s("h", SessionStatus.UNKNOWN)))
        // A closed managed terminal is offline without send: managed alone does not make it open.
        assertFalse(SessionBrowser.isOpen(s("i", SessionStatus.OFFLINE, managed = true)))
        assertFalse(SessionBrowser.isOpen(s("j", SessionStatus.COMPLETED, managed = true)))
    }

    @Test
    fun openScopeHidesHistoryAndAllShowsEverything() {
        val list = listOf(
            s("live", SessionStatus.IDLE, live = true),
            s("old", SessionStatus.OFFLINE, live = false),
            s("mystery", SessionStatus.UNKNOWN),
        )
        val open = SessionBrowser.browse(list)
        assertEquals(listOf("live"), open.ids())
        assertEquals(1, open.chats)
        assertEquals(2, open.hiddenByScope)
        val all = SessionBrowser.browse(list, BrowserQuery(scope = BrowserScope.ALL))
        assertEquals(setOf("live", "old", "mystery"), all.ids().toSet())
        assertEquals(3, all.chats)
        assertEquals(0, all.hiddenByScope)
    }

    @Test
    fun allScopeKeepsEveryFixtureSessionReachable() {
        val all = SessionBrowser.browse(fixture, BrowserQuery(scope = BrowserScope.ALL), expanded = setOf(claudeParent, codexParent))
        assertEquals(fixture.map { it.id }.toSet(), all.ids().toSet())
        assertEquals(fixture.size, all.chats + all.childAgents)
    }

    @Test
    fun openKeepsHistoricalParentAsContextForOpenChild() {
        // Codex parent: idle, live=false (history). Its child Parfit is working.
        val r = SessionBrowser.browse(fixture, expanded = setOf(codexParent))
        val parent = r.chat(codexParent)
        assertTrue(parent.context)
        assertEquals(1, parent.childCount)
        assertEquals(listOf("Parfit"), r.rows.filterIsInstance<AgentRow>().filter { it.session.parentSessionId == codexParent }.map { it.session.agentName })
        // Context parents are not counted as matching chats.
        assertEquals(listOf("Szillus copy edits", "Agent Deck build"), r.rows.filterIsInstance<ChatRow>().filter { !it.context }.map { it.session.title })
        assertEquals(2, r.chats)
        // Guardian (unknown, no live evidence) is history-only: absent from Open, present in All.
        assertFalse(r.ids().any { it.endsWith("0000000000ff") })
        assertTrue(SessionBrowser.browse(fixture, BrowserQuery(scope = BrowserScope.ALL)).ids().any { it.endsWith("0000000000ff") })
    }

    @Test
    fun searchRevealsMatchingDescendantWithRealAncestorsWithoutTouchingExpansion() {
        val list = listOf(
            s("root", title = "Main chat", live = true),
            s("mid", parent = "root", name = "Planner", status = SessionStatus.COMPLETED),
            s("leaf", parent = "mid", name = "Lint runner", task = "Run detekt", status = SessionStatus.WORKING),
            s("other", parent = "root", name = "Docs", status = SessionStatus.WORKING),
        )
        val expanded = emptySet<String>()
        val r = SessionBrowser.browse(list, BrowserQuery(scope = BrowserScope.ALL, search = "  DETEKT "), expanded)
        assertEquals(listOf("root", "mid", "leaf"), r.ids())
        val root = r.chat("root")
        assertTrue(root.context)
        assertTrue(root.revealedBySearch)
        assertFalse(root.expanded)
        assertTrue(r.agent("mid").context)
        assertFalse(r.agent("leaf").context)
        assertEquals(2, r.agent("leaf").depth)
        assertEquals(0, r.chats)
        assertEquals(1, r.childAgents)
        assertTrue(expanded.isEmpty())
        // Clearing the search returns to the saved (collapsed) state.
        assertEquals(listOf("root"), SessionBrowser.browse(list, BrowserQuery(scope = BrowserScope.ALL), expanded).ids())
    }

    @Test
    fun searchMatchesTitleProviderFolderAgentNameAndTask() {
        fun hits(q: String) = SessionBrowser.browse(fixture, BrowserQuery(scope = BrowserScope.ALL, search = q)).let { it.chats + it.childAgents }
        assertEquals(1, hits("szillus copy"))
        assertEquals(5, hits("codex")) // provider: parent, 3 children, guardian
        assertEquals(1, hits("parfit"))
        assertEquals(1, hits("token handling")) // task of "Review backend auth"
        assertEquals(1, hits("Szillus")) // folder name and title of the same chat count once
        assertEquals(0, hits("no such thing"))
    }

    @Test
    fun providerFilterAndHiddenCounts() {
        val r = SessionBrowser.browse(fixture, BrowserQuery(scope = BrowserScope.ALL, provider = Agents.CODEX))
        assertTrue(r.rows.filterIsInstance<ChatRow>().all { it.session.agent == Agents.CODEX })
        assertEquals(5, r.hiddenByFilters) // 2 Claude chats + 3 Claude child agents
        val claude = SessionBrowser.browse(fixture, BrowserQuery(scope = BrowserScope.ALL, provider = Agents.CLAUDE, search = "parfit"))
        assertTrue(claude.isEmpty)
        assertEquals(1, claude.hiddenByFilters)
    }

    @Test
    fun unknownProviderOnlyUnderAllProviders() {
        val list = listOf(s("x", agent = "gemini", live = true))
        assertEquals(listOf("x"), SessionBrowser.browse(list).ids())
        assertTrue(SessionBrowser.browse(list, BrowserQuery(provider = Agents.CLAUDE)).isEmpty)
    }

    @Test
    fun collapsedFolderHidesRowsButSearchRevealsThem() {
        val list = listOf(s("a", cwd = "/x/one", live = true), s("b", cwd = "/x/two", live = true, title = "Needle"))
        val collapsed = SessionBrowser.browse(list, collapsedFolders = setOf("/x/two"))
        val folder = collapsed.rows.filterIsInstance<FolderRow>().single { it.folder == "/x/two" }
        assertTrue(folder.collapsed)
        assertEquals(1, folder.chats)
        assertEquals(listOf("a"), collapsed.ids())
        // Counts still describe the collapsed folder truthfully.
        assertEquals(2, collapsed.chats)
        val searched = SessionBrowser.browse(list, BrowserQuery(search = "needle"), collapsedFolders = setOf("/x/two"))
        assertEquals(listOf("b"), searched.ids())
        val f = searched.rows.filterIsInstance<FolderRow>().single()
        assertFalse(f.collapsed)
        assertTrue(f.revealedBySearch)
    }

    @Test
    fun noFalseParentLinksOrCycles() {
        val list = listOf(
            s("p", live = true), s("c", parent = "p", live = true),
            s("x", parent = "y", live = true), s("y", parent = "x", live = true),
            s("self", parent = "self", live = true), s("orphan", parent = "gone", live = true),
        )
        assertEquals(mapOf("c" to "p"), SessionBrowser.parentLinks(list))
        val r = SessionBrowser.browse(list, expanded = setOf("p"))
        assertEquals(setOf("p", "x", "y", "self", "orphan"), r.rows.filterIsInstance<ChatRow>().map { it.session.id }.toSet())
        assertEquals(listOf("c"), r.rows.filterIsInstance<AgentRow>().map { it.session.id })
        // Every listed session appears exactly once.
        assertEquals(r.ids().size, r.ids().toSet().size)
    }

    @Test
    fun activeFilterStillSupportedWithoutHidingParentsOfActiveChildren() {
        val r = SessionBrowser.browse(fixture, BrowserQuery(scope = BrowserScope.ALL, activity = ActivityFilter.ACTIVE), expanded = setOf(claudeParent))
        assertTrue(r.chat(codexParent).context)
        assertEquals(listOf("Search model docs"), r.rows.filterIsInstance<AgentRow>().map { it.session.title })
    }

    @Test
    fun mixedProviderChildKeepsItsRealParentAsContext() {
        val sessions = listOf(s("p", agent = "claude", live = true), s("c", parent = "p", agent = "codex", live = true, name = "Research"))
        val result = SessionBrowser.browse(sessions, BrowserQuery(provider = "codex", search = "research"))
        assertEquals(listOf("p", "c"), result.ids())
        assertTrue(result.chat("p").context)
        assertEquals(1, result.childAgents)
        assertEquals(0, result.chats)
    }

    @Test
    fun fullProjectPathCanBeSearched() {
        val result = SessionBrowser.browse(listOf(s("p", cwd = "/work/Client Projects/Website", live = true)), BrowserQuery(search = "client projects"))
        assertEquals(listOf("p"), result.ids())
    }
}
