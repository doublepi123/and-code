package com.yugahashimoto.andcode.runtime.local

/**
 * Keeps the watchdog's idle auto-stop from overriding a newer command intent.
 *
 * Every command the service receives calls [onCommand], which advances a generation. An idle stop
 * takes its token from [beginIdleStop] at the generation it was decided at, and [isCurrent] reports
 * whether nothing newer has arrived since. The stop only tears the service down while that still
 * holds; a start that arrives in the meantime wins and the service stays up.
 *
 * Kept free of Android types so the interleavings can be tested directly.
 */
internal class IdleStopGate {
    private val lock = Any()
    private var generation = 0L
    private var pendingToken: Long? = null

    /** Records a newer command intent and returns the generation it produced. */
    fun onCommand(): Long =
        synchronized(lock) {
            generation++
            generation
        }

    /** Starts an idle stop decision. Returns its token, or null when one is already pending. */
    fun beginIdleStop(): Long? =
        synchronized(lock) {
            if (pendingToken != null) {
                null
            } else {
                generation.also { pendingToken = it }
            }
        }

    /** Whether no command has arrived since [token] was taken. */
    fun isCurrent(token: Long): Boolean = synchronized(lock) { generation == token }

    /** Clears the pending idle stop for [token]. A token that is no longer pending is ignored. */
    fun finishIdleStop(token: Long) {
        synchronized(lock) {
            if (pendingToken == token) pendingToken = null
        }
    }
}
