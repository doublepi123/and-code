package com.yugahashimoto.andcode.runtime.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Interleavings of the watchdog's idle stop against an incoming command, reproduced on the
 * [IdleStopGate] state machine without Android. The two regressions these guard against:
 *
 * 1. A foreground return landing after the idle stop decided to stop but before anything marked it
 *    in progress must still see the stop as pending, not skip the restore.
 * 2. A start that arrives while an idle stop is still running must win: the finished stop may not
 *    tear the service down underneath it.
 */
class IdleStopGateTest {
    @Test
    fun `an idle stop with no newer command is still current when it completes`() {
        val gate = IdleStopGate()
        val token = gate.beginIdleStop()
        assertNotNull(token)

        assertTrue(gate.isCurrent(token!!))
        gate.finishIdleStop(token)
    }

    /** A start arriving while the idle stop waits on the manager lock supersedes it. */
    @Test
    fun `a start that arrives during the idle stop supersedes it`() {
        val gate = IdleStopGate()
        val token = gate.beginIdleStop()!!

        gate.onCommand()

        assertFalse(gate.isCurrent(token))
    }

    /** Once superseded, a later idle stop decision is current again and is allowed to proceed. */
    @Test
    fun `a fresh idle stop after a superseded one is current`() {
        val gate = IdleStopGate()
        val first = gate.beginIdleStop()!!
        gate.onCommand()
        gate.finishIdleStop(first)

        val second = gate.beginIdleStop()

        assertNotNull(second)
        assertTrue(gate.isCurrent(second!!))
    }

    /** Only one idle stop may be pending at a time; a second decision is refused, not stacked. */
    @Test
    fun `a second idle stop is refused while one is pending`() {
        val gate = IdleStopGate()
        assertNotNull(gate.beginIdleStop())

        assertNull(gate.beginIdleStop())
    }

    /** A finish for a token that is no longer pending must not clear a newer pending stop. */
    @Test
    fun `finishing a stale token does not clear a newer pending stop`() {
        val gate = IdleStopGate()
        val stale = gate.beginIdleStop()!!
        gate.onCommand()
        gate.finishIdleStop(stale)
        val fresh = gate.beginIdleStop()!!

        gate.finishIdleStop(stale)

        assertNull(gate.beginIdleStop())
        assertTrue(gate.isCurrent(fresh))
    }

    /** The generation only moves forward, so tokens from before a command never match again. */
    @Test
    fun `commands advance the generation monotonically`() {
        val gate = IdleStopGate()
        val first = gate.onCommand()
        val second = gate.onCommand()

        assertEquals(first + 1, second)
    }
}
