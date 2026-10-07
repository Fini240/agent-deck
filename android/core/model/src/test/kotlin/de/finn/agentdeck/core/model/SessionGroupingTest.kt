package de.finn.agentdeck.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionGroupingTest {
    private val fixture: List<Session> = AgentDeckJson.decodeFromString(
        SessionsResponse.serializer(),
        javaClass.getResource("/sessions-with-subagents.json")!!.readText(),
    ).sessions

    private fun s(id: String, status: SessionStatus = SessionStatus.IDLE, parent: String? = null, cwd: String = "/p", root: String? = null, at: String = "2026-10-07T00:00:00Z") =
        Session(id = id, agent = "claude", cwd = cwd, status = status, parentSessionId = parent, isSubagent = parent != null, projectRoot = root, updatedAt = at)

    @Test
    fun parsesNewOptionalFields() {
        val child = fixture.first { it.nativeId == "a0000000000000003" }
        assertTrue(child.isSubagent)
        assertEquals("claude:11111111-2222-4333-8444-555555555555", child.parentSessionId)
        assertEquals("Explore", child.agentRole)
        assertEquals("Search model docs", child.agentName)
        assertEquals(0.4f, child.progress!!.fraction!!, 0.0001f)
        assertEquals("/Users/test/proj", child.projectRoot)
    }

    @Test
    fun parsesOldServerShapeWithDefaults() {
        val json = """{"sessions":[{"id":"x","agent":"codex","nativeId":null,"title":"t","cwd":"/a/b","model":null,
            "status":"idle","stage":null,"progress":null,"updatedAt":"2026-10-06T00:00:00Z","managed":false,
            "capabilities":{"send":false,"interrupt":false,"approve":false,"stop":false},"lastMessage":null,"unread":0}]}"""
        val s = AgentDeckJson.decodeFromString(SessionsResponse.serializer(), json).sessions.single()
        assertFalse(s.isSubagent)
        assertNull(s.parentSessionId)
        assertNull(s.agentName)
        assertFalse(s.canResume)
        assertEquals("/a/b", s.projectKey)
    }

    @Test
    fun groupsFixtureByProjectWithRealChildren() {
        val groups = SessionGrouping.group(fixture)
        assertEquals(listOf("szillus", "proj"), groups.map { it.label }) // needs_input project first
        val proj = groups.single { it.label == "proj" }
        // Codex parent's cwd is a subfolder, but projectRoot puts it in the same group.
        val claudeParent = proj.entries.first { it.session.agent == "claude" }
        assertEquals(3, claudeParent.totalChildren)
        assertEquals(1, claudeParent.activeChildren)
        assertEquals(
            listOf(SessionStatus.WORKING, SessionStatus.ERROR, SessionStatus.COMPLETED),
            claudeParent.children.map { it.session.status },
        )
        val codexParent = proj.entries.first { it.session.id == "codex:01a10000-0000-7000-8000-000000000001" }
        assertEquals(3, codexParent.totalChildren)
        // Guardian has no parent link: it stays top-level instead of being attached to a guessed parent.
        assertTrue(proj.entries.any { it.session.agentName == "guardian" && it.session.isSubagent })
        assertEquals(10, groups.sumOf { it.sessionCount })
    }

    @Test
    fun activeFilterKeepsParentsOfActiveChildrenOnly() {
        val groups = SessionGrouping.group(fixture, ActivityFilter.ACTIVE)
        val all = groups.flatMap { it.entries }
        // Codex parent is idle but has an active child (Parfit), so it stays; guardian (unknown) is hidden.
        assertTrue(all.any { it.session.id == "codex:01a10000-0000-7000-8000-000000000001" })
        assertFalse(all.any { it.session.agentName == "guardian" })
        val codex = all.first { it.session.id.startsWith("codex:") }
        assertEquals(listOf("Parfit"), codex.children.map { it.session.agentName })
        assertEquals(3, codex.totalChildren) // counts still describe all children
    }

    @Test
    fun childStatusIsNeverCopiedFromParent() {
        val groups = SessionGrouping.group(listOf(s("p", SessionStatus.WORKING), s("c", SessionStatus.UNKNOWN, parent = "p")))
        assertEquals(SessionStatus.UNKNOWN, groups.single().entries.single().children.single().session.status)
        assertEquals(0, groups.single().entries.single().activeChildren)
    }

    @Test
    fun nestedChildrenGetDepthAndCyclesAreSafe() {
        val list = listOf(s("p"), s("c1", parent = "p"), s("c2", parent = "c1"), s("x", parent = "y"), s("y", parent = "x"))
        val entries = SessionGrouping.group(list).single().entries
        val p = entries.first { it.session.id == "p" }
        assertEquals(listOf("c1" to 1, "c2" to 2), p.children.map { it.session.id to it.depth })
        // x and y point at each other: the link is ignored and both stay visible as top-level rows.
        assertTrue(entries.any { it.session.id == "x" } && entries.any { it.session.id == "y" })
    }

    @Test
    fun selfParentAndMissingParentStayTopLevel() {
        val entries = SessionGrouping.group(listOf(s("a", parent = "a"), s("b", parent = "gone"))).single().entries
        assertEquals(setOf("a", "b"), entries.map { it.session.id }.toSet())
        // A parent whose own parent is missing is still a valid root for its children.
        val chain = SessionGrouping.group(listOf(s("p", parent = "gone"), s("c", parent = "p"))).single().entries.single()
        assertEquals("p", chain.session.id)
        assertEquals(listOf("c"), chain.children.map { it.session.id })
    }

    @Test
    fun projectKeyFallsBackToCwdAndAgentFilterApplies() {
        val groups = SessionGrouping.group(listOf(s("a", cwd = "/x/one/"), s("b", cwd = "/x/two", root = "/x/one")))
        assertEquals(1, groups.size)
        assertEquals("one", groups.single().label)
        assertTrue(SessionGrouping.group(fixture, agent = "codex").flatMap { it.entries }.all { it.session.agent == "codex" })
    }
}
