package de.finn.agentdeck.core.model

/** A top-level session with its real child agents (from `parentSessionId`). */
data class ParentEntry(
    val session: Session,
    /** Children in display order; [depth] gives each one's nesting level (1 = direct child). */
    val children: List<ChildEntry>,
    val totalChildren: Int,
    val activeChildren: Int,
)

data class ChildEntry(val session: Session, val depth: Int)

data class ProjectGroup(
    val key: String,
    val label: String,
    val entries: List<ParentEntry>,
) {
    val activeCount: Int get() = entries.sumOf { (if (it.session.isActive) 1 else 0) + it.activeChildren }
    val sessionCount: Int get() = entries.sumOf { 1 + it.totalChildren }
}

enum class ActivityFilter { ALL, ACTIVE, ATTENTION }

fun ActivityFilter.matches(s: Session): Boolean = when (this) {
    ActivityFilter.ALL -> true
    ActivityFilter.ACTIVE -> s.isActive
    ActivityFilter.ATTENTION -> s.status == SessionStatus.NEEDS_INPUT || s.status == SessionStatus.ERROR
}

object SessionGrouping {
    /**
     * Project → top-level sessions → child agents.
     *
     * Only explicit `parentSessionId` links make a child; a subagent whose parent is not in the
     * list stays top-level rather than being attached to a guessed parent. With [ActivityFilter.ACTIVE]
     * a parent is kept if it or any descendant is active, and only active children are listed
     * (counts still describe all children).
     */
    fun group(sessions: List<Session>, filter: ActivityFilter = ActivityFilter.ALL, agent: String = "all"): List<ProjectGroup> {
        val byId = sessions.associateBy { it.id }
        // A parent link counts only if the ancestor chain ends at a real root (no missing parent, no cycle).
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
        val childrenOf = sessions.filter(::linked).groupBy { it.parentSessionId!! }
        val attached = childrenOf.values.flatten().map { it.id }.toSet()

        fun descendants(id: String, depth: Int, seen: MutableSet<String>): List<ChildEntry> =
            childrenOf[id].orEmpty().sortedWith(ORDER).flatMap { c ->
                if (!seen.add(c.id)) emptyList() else listOf(ChildEntry(c, depth)) + descendants(c.id, depth + 1, seen)
            }

        val tops = sessions.filter { it.id !in attached && (agent == "all" || it.agent == agent) }
        val entries = tops.map { top ->
            val all = descendants(top.id, 1, mutableSetOf(top.id))
            val active = all.count { it.session.isActive }
            val shown = all.filter { filter.matches(it.session) }
            ParentEntry(top, shown, all.size, active)
        }.filter { filter.matches(it.session) || it.children.isNotEmpty() }

        return entries.groupBy { it.session.projectKey }
            .map { (key, list) -> ProjectGroup(key, label(key), list.sortedWith(compareBy(ORDER) { it.session })) }
            .sortedWith(
                compareBy<ProjectGroup> { g -> g.entries.minOf { rank(it.session, it.activeChildren) } }
                    .thenByDescending { g -> g.entries.maxOf { it.session.updatedAt.orEmpty() } },
            )
    }

    fun label(key: String): String = key.trimEnd('/').substringAfterLast('/').ifBlank { key }

    /** Needs input, then working, then error, then the rest; newest first within a rank. */
    val ORDER: Comparator<Session> = compareBy<Session> { rank(it, 0) }.thenByDescending { it.updatedAt.orEmpty() }

    private fun rank(s: Session, activeChildren: Int): Int = when {
        s.status == SessionStatus.NEEDS_INPUT -> 0
        s.status == SessionStatus.WORKING || activeChildren > 0 -> 1
        s.status == SessionStatus.ERROR -> 2
        else -> 3
    }
}
