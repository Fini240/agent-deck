package de.finn.agentdeck.core.model

/** Which chats the browser lists: only ones that are still open, or everything including history. */
enum class BrowserScope { OPEN, ALL }

data class BrowserQuery(
    val scope: BrowserScope = BrowserScope.OPEN,
    /** Provider filter: all | claude | codex (unknown providers only appear under all). */
    val provider: String = "all",
    val activity: ActivityFilter = ActivityFilter.ALL,
    val search: String = "",
    val pinned: Set<String> = emptySet(),
    val pinnedOnly: Boolean = false,
) {
    val needle: String get() = search.trim().lowercase()
    val searching: Boolean get() = needle.isNotEmpty()
    val filtersSet: Boolean get() = provider != "all" || activity != ActivityFilter.ALL || pinnedOnly
    /** Focused views reveal matches without changing saved folder or child disclosure. */
    val revealing: Boolean get() = searching || pinnedOnly || activity == ActivityFilter.ATTENTION
}

/** One line of the flattened browser list, in display order. */
sealed interface BrowserRow {
    val key: String
}

data class FolderRow(
    /** Project key (normalized root or cwd) used for the saved collapse state. */
    val folder: String,
    val label: String,
    /** Top-level chats in this folder that match the scope, filters and search. */
    val chats: Int,
    /** Child agents in this folder that match the scope, filters and search. */
    val childAgents: Int,
    /** Of those, how many are working or need input. */
    val active: Int,
    /** True when the folder's rows are hidden (the saved collapse applies and no focused view overrides it). */
    val collapsed: Boolean,
    /** True when a focused view temporarily forces the folder open regardless of the saved state. */
    val revealedBySearch: Boolean,
) : BrowserRow {
    override val key: String get() = "g:" + folder
}

data class ChatRow(
    val session: Session,
    /** Matching child agents under this chat (all descendants, not only the ones currently listed). */
    val childCount: Int,
    val activeChildren: Int,
    /** Saved disclosure state. */
    val expanded: Boolean,
    /** Children are listed because a focused view matched them, independent of [expanded]. */
    val revealedBySearch: Boolean,
    /** Listed only as the real parent of a matching child; the chat itself is outside the scope or search. */
    val context: Boolean,
) : BrowserRow {
    override val key: String get() = "s:" + session.id
}

data class AgentRow(
    val session: Session,
    /** 1 = direct child of the chat. */
    val depth: Int,
    /** Listed only as the real ancestor of a deeper match. */
    val context: Boolean,
) : BrowserRow {
    override val key: String get() = "c:" + session.id
}

data class BrowserResult(
    val rows: List<BrowserRow>,
    /** Matching top-level chats (context-only parents excluded). */
    val chats: Int,
    /** Matching child agents (context-only ancestors excluded), listed or collapsed. */
    val childAgents: Int,
    /** Sessions that match provider, activity and search but are history, so only [BrowserScope.ALL] shows them. */
    val hiddenByScope: Int,
    /** Sessions that match scope and search but are excluded by the provider or activity filter. */
    val hiddenByFilters: Int,
    val total: Int,
) {
    val isEmpty: Boolean get() = chats + childAgents == 0
}

object SessionBrowser {
    /**
     * Open means something is still running or can be replied to: working or needing input,
     * live process evidence, or a server-granted send capability. Offline (history only) is never open,
     * and `managed` alone is not evidence: a closed managed terminal reports offline without send.
     */
    fun isOpen(s: Session): Boolean = when {
        s.status == SessionStatus.OFFLINE -> false
        s.isActive -> true
        s.capabilities.send -> true
        else -> s.live == true
    }

    /** Case-insensitive match on title, provider, folder name, agent name/role and task. */
    fun matches(s: Session, needle: String): Boolean {
        if (needle.isEmpty()) return true
        val fields = listOfNotNull(
            s.title, s.agent, Agents.displayName(s.agent), SessionGrouping.label(s.projectKey),
            s.projectKey, s.cwd, s.agentName, s.agentRole, s.task,
        )
        return fields.any { it.lowercase().contains(needle) }
    }

    /** Real parent links only: the chain must end at a present root, no missing parent and no cycle. */
    fun parentLinks(sessions: List<Session>): Map<String, String> {
        val byId = sessions.associateBy { it.id }
        fun linked(s: Session): Boolean {
            val first = s.parentSessionId ?: return false
            val parent = byId[first] ?: return false
            val seen = mutableSetOf(s.id, first)
            var cur = parent.parentSessionId
            while (cur != null && byId.containsKey(cur)) {
                if (!seen.add(cur)) return false
                cur = byId.getValue(cur).parentSessionId
            }
            return true
        }
        return sessions.filter(::linked).associate { it.id to it.parentSessionId!! }
    }

    private class Node(val session: Session, val depth: Int, val children: List<Node>)

    fun browse(
        sessions: List<Session>,
        query: BrowserQuery = BrowserQuery(),
        expanded: Set<String> = emptySet(),
        collapsedFolders: Set<String> = emptySet(),
    ): BrowserResult {
        val needle = query.needle
        val links = parentLinks(sessions)
        val childrenOf = sessions.filter { it.id in links }.groupBy { links.getValue(it.id) }

        fun build(s: Session, depth: Int, seen: MutableSet<String>): Node =
            Node(s, depth, childrenOf[s.id].orEmpty().sortedWith(SessionGrouping.ORDER).filter { seen.add(it.id) }.map { build(it, depth + 1, seen) })

        fun providerOk(root: Session) = query.provider == "all" || root.agent == query.provider
        fun activityOk(s: Session) = query.activity.matches(s)
        fun scopeOk(s: Session) = query.scope == BrowserScope.ALL || isOpen(s)
        fun pinOk(s: Session): Boolean {
            if (!query.pinnedOnly) return true
            var id: String? = s.id
            while (id != null) {
                if (id in query.pinned) return true
                id = links[id]
            }
            return false
        }

        val roots = sessions.filter { it.id !in links }.map { build(it, 0, mutableSetOf(it.id)) }

        var hiddenByScope = 0
        var hiddenByFilters = 0
        fun hit(n: Node): Boolean {
            val s = n.session
            if (!matches(s, needle)) return false
            val filters = providerOk(s) && activityOk(s) && pinOk(s)
            val scope = scopeOk(s)
            if (filters && !scope) hiddenByScope++
            if (scope && !filters) hiddenByFilters++
            return filters && scope
        }

        data class Built(val row: ChatRow, val children: List<AgentRow>, val hit: Boolean, val activeHit: Boolean)

        val built = roots.mapNotNull { root ->
            val hits = HashMap<String, Boolean>()
            fun mark(n: Node): Boolean {
                val self = hit(n).also { hits[n.session.id] = it }
                var below = false
                n.children.forEach { if (mark(it)) below = true }
                return self || below
            }
            val anyVisible = mark(root)
            if (!anyVisible) return@mapNotNull null

            fun hasHitBelow(n: Node): Boolean = n.children.any { hits.getValue(it.session.id) || hasHitBelow(it) }
            fun flatten(n: Node): List<Node> = n.children.flatMap { listOf(it) + flatten(it) }
            val descendants = flatten(root)
            val childHits = descendants.filter { hits.getValue(it.session.id) }

            val isExpanded = root.session.id in expanded
            val reveal = query.revealing && childHits.isNotEmpty()
            val listed = if (reveal || isExpanded) {
                descendants.filter { hits.getValue(it.session.id) || hasHitBelow(it) }
                    .map { AgentRow(it.session, it.depth, context = !hits.getValue(it.session.id)) }
            } else {
                emptyList()
            }
            val selfHit = hits.getValue(root.session.id)
            Built(
                ChatRow(
                    root.session, childHits.size, childHits.count { it.session.isActive },
                    expanded = isExpanded, revealedBySearch = reveal, context = !selfHit,
                ),
                listed, selfHit, selfHit && root.session.isActive,
            )
        }

        val rows = mutableListOf<BrowserRow>()
        val folders = built.groupBy { it.row.session.projectKey }
            .toList()
            .sortedWith(
                compareBy<Pair<String, List<Built>>> { (_, list) -> list.minOf { rank(it.row) } }
                    .thenByDescending { (_, list) -> list.maxOf { it.row.session.updatedAt.orEmpty() } },
            )
        folders.forEach { (key, list) ->
            val sorted = list.sortedWith(compareBy<Built> { rank(it.row) }.thenBy { if (it.row.session.id in query.pinned) 0 else 1 }.thenByDescending { it.row.session.updatedAt.orEmpty() })
            val savedCollapsed = key in collapsedFolders
            val revealed = savedCollapsed && query.revealing
            rows += FolderRow(
                folder = key, label = SessionGrouping.label(key),
                chats = sorted.count { it.hit }, childAgents = sorted.sumOf { it.row.childCount },
                active = sorted.sumOf { (if (it.activeHit) 1 else 0) + it.row.activeChildren },
                collapsed = savedCollapsed && !query.revealing, revealedBySearch = revealed,
            )
            if (!savedCollapsed || query.revealing) {
                sorted.forEach { b ->
                    rows += b.row
                    rows += b.children
                }
            }
        }

        return BrowserResult(
            rows = rows,
            chats = built.count { it.hit },
            childAgents = built.sumOf { it.row.childCount },
            hiddenByScope = hiddenByScope,
            hiddenByFilters = hiddenByFilters,
            total = sessions.size,
        )
    }

    /** Needs input, then working (or has a working child), then error, then the rest. */
    private fun rank(r: ChatRow): Int = when {
        r.session.status == SessionStatus.NEEDS_INPUT -> 0
        r.session.status == SessionStatus.WORKING || r.activeChildren > 0 -> 1
        r.session.status == SessionStatus.ERROR -> 2
        else -> 3
    }
}
