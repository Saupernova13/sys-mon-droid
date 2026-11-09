package com.raavivi.sysmon.core.tracker.cmd

/** Remembers the last [max] command ids so each command runs once. */
class HandledIds(initial: List<String> = emptyList(), private val max: Int = 50) {
    private val ids = LinkedHashSet<String>(initial.takeLast(max))

    /** True the first time [id] is seen, false for repeats. */
    @Synchronized
    fun markIfNew(id: String): Boolean {
        if (!ids.add(id)) return false
        while (ids.size > max) ids.remove(ids.first())
        return true
    }

    @Synchronized
    fun snapshot(): List<String> = ids.toList()
}
