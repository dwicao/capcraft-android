package io.github.artcraftandroid.capcraft.engine

import io.github.artcraftandroid.capcraft.core.MobileSession
import io.github.artcraftandroid.capcraft.core.TimelineView

/**
 * One shared [MobileSession] per open project folder, used by both the
 * editor UI and the agent API — so an AI agent's edits land live in the
 * open editor instead of in a second copy of the project.
 *
 * The Rust session locks internally and every caller runs on a worker
 * dispatcher, so sharing the object is thread-safe. Ownership is reference
 * counted: [acquire] bumps the count (opening on first use), [release]
 * drops it. The last holder's release just forgets the entry; the UniFFI
 * object (Arc-backed) frees itself once nobody holds it.
 *
 * After a mutating call, the editor of that project is told through
 * [subscribe]/[publish]: the subscriber gets the fresh [TimelineView] and
 * swaps it in on the main thread. Undo history is shared too — one
 * timeline, one history, whoever edits it.
 */
object SessionHub {
    private val lock = Any()
    private val sessions = HashMap<String, MobileSession>()
    private val refs = HashMap<String, Int>()
    private val listeners = HashMap<String, MutableSet<(TimelineView) -> Unit>>()

    /**
     * The session for [path], opened with [open] on first use. A caller
     * that receives a session owns one reference and must pair it with
     * [release]. The opener runs outside the hub lock (opening warms
     * scrub copies and blocks); two racers may both open, in which case
     * the loser is dropped and the winner is shared.
     */
    fun get(path: String): MobileSession? = synchronized(lock) { sessions[path] }

    /**
     * Stores [fresh] as [path]'s session, or — if a racer stored one
     * first — drops [fresh] and returns the winner. Either way the
     * caller owns one reference on the returned object.
     */
    fun publishNew(path: String, fresh: MobileSession): MobileSession = synchronized(lock) {
        val existing = sessions[path]
        if (existing != null) {
            refs[path] = (refs[path] ?: 0) + 1
            existing
        } else {
            sessions[path] = fresh
            refs[path] = 1
            fresh
        }
    }

    /** Drops one reference from [acquire]. */
    fun release(path: String, session: MobileSession) = synchronized(lock) {
        if (sessions[path] !== session) return@synchronized
        val left = (refs[path] ?: 1) - 1
        if (left <= 0) {
            sessions.remove(path)
            refs.remove(path)
        } else {
            refs[path] = left
        }
    }

    /** How many projects currently share a session (for status). */
    fun size(): Int = synchronized(lock) { sessions.size }

    fun subscribe(path: String, listener: (TimelineView) -> Unit) = synchronized(lock) {
        listeners.getOrPut(path) { mutableSetOf() }.add(listener)
    }

    fun unsubscribe(path: String, listener: (TimelineView) -> Unit) = synchronized(lock) {
        listeners[path]?.remove(listener)
        if (listeners[path].isNullOrEmpty()) listeners.remove(path)
    }

    /** Hands a fresh view of [path] to its editor, if one listens. */
    fun publish(path: String, view: TimelineView) {
        val targets: List<(TimelineView) -> Unit> = synchronized(lock) {
            listeners[path]?.toList().orEmpty()
        }
        targets.forEach { runCatching { it(view) } }
    }
}
