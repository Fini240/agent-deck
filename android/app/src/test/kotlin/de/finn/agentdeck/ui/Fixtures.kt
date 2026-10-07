package de.finn.agentdeck.ui

import de.finn.agentdeck.core.model.AgentDeckJson
import de.finn.agentdeck.core.model.AgentModels
import de.finn.agentdeck.core.model.Approval
import de.finn.agentdeck.core.model.ApprovalChoice
import de.finn.agentdeck.core.model.Message
import de.finn.agentdeck.core.model.ModelOption
import de.finn.agentdeck.core.model.ServerSettings
import de.finn.agentdeck.core.model.ServerStatus
import de.finn.agentdeck.core.model.Session
import de.finn.agentdeck.core.model.SessionsResponse
import java.io.File
import java.time.Instant

/** Realistic data shared by screenshot and UI tests (same file the core grouping test uses). */
object Fixtures {
    val now: Instant = Instant.parse("2026-10-07T09:13:00Z")

    val sessions: List<Session> = AgentDeckJson.decodeFromString(
        SessionsResponse.serializer(),
        File(System.getProperty("user.dir"), "../core/model/src/test/resources/sessions-with-subagents.json").readText(),
    ).sessions

    val claudeParent = sessions.first { it.id == "claude:11111111-2222-4333-8444-555555555555" }
    val workingChild = sessions.first { it.nativeId == "a0000000000000003" }
    val needsInput = sessions.first { it.status.wire == "needs_input" }

    val parentMessages = listOf(
        Message("m1", "user", "Split the Android work: review auth, build the grouped list, and check the model docs.", "2026-10-07T09:02:00Z"),
        Message("m2", "assistant", "I'll start three agents in parallel and keep the main thread for integration.", "2026-10-07T09:02:20Z"),
        Message("m3", "tool", "Agent(description=\"Search model docs\", subagent_type=\"Explore\")\n→ started a0000000000000003", "2026-10-07T09:02:25Z", "Agent"),
        Message("m4", "assistant", "Auth review is done (2 suggestions). The list build failed on an unresolved reference; I'm fixing it now.", "2026-10-07T09:11:50Z"),
    )

    val childMessages = listOf(
        Message("c1", "user", "Find the model list format used by the Claude CLI and summarise it.", "2026-10-07T09:02:25Z"),
        Message("c2", "tool", "WebFetch https://docs.example.com/models\n200 OK · 18 KB", "2026-10-07T09:05:10Z", "WebFetch"),
        Message("c3", "assistant", "Reading release notes… 4 of 10 pages checked.", "2026-10-07T09:11:30Z"),
    )

    val approval = Approval(
        "ap-1", needsInput.id, "Allow Bash command?", "npm test -- --runInBand",
        listOf(ApprovalChoice("yes", "Yes"), ApprovalChoice("always", "Yes, don't ask again"), ApprovalChoice("no", "No")),
    )

    val models = listOf(
        AgentModels(
            "claude", "Claude Code", true, "2.1.285",
            listOf(ModelOption("opus", "Opus", "Most capable", "cli"), ModelOption("sonnet", "Sonnet", "Fast and capable", "cli"), ModelOption("haiku", "Haiku", null, "cli")),
            "live: claude CLI", "2026-10-07T09:10:00Z",
        ),
        AgentModels("codex", "Codex", true, "0.160.1", listOf(ModelOption("gpt-5-codex", "gpt-5-codex", null, "cache")), "cached", "2026-10-07T08:00:00Z"),
    )

    val serverSettings = ServerSettings(
        allowedWorkspaces = listOf("/Users/finn/Documents/Codex", "/Users/finn/Developer"),
        defaultAgent = "claude", notifyOn = listOf("completed", "error", "input"), progressIntervalSeconds = 20, keepAwakeMode = "active",
    )

    val serverStatus: ServerStatus = AgentDeckJson.decodeFromString(
        ServerStatus.serializer(),
        """{"version":"0.1.0","push":{"available":true,"reason":null,"projectId":"example-agent-deck","devicesWithToken":1,"sent":3},
           "keepAwake":{"held":true,"mode":"active","error":null},"providers":{"available":true,"error":null},
           "terminal":{"available":true,"error":null},"monitor":{"refreshedAt":"2026-10-07T09:12:58Z","error":null},"sseClients":1}""",
    )
}
