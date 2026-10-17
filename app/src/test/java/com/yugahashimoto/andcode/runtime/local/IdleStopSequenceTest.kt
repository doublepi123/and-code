package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.runtime.LocalRuntimeStatus
import com.yugahashimoto.andcode.startup.shouldRestoreOnForegroundReturn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [runIdleStopSequence] is [LocalRuntimeService.checkIdleStop]'s set-stop-clear sequence pulled out
 * so it can be exercised without a real Android [android.app.Service]. The regression it guards
 * against: the previous round called `app.setIdleStopInProgress(true)` outside the coroutine that
 * eventually cleared it, so a `scope.cancel()` (from `onDestroy`) landing between scheduling that
 * coroutine and it actually starting left the flag stuck `true` for the rest of the process.
 */
class IdleStopSequenceTest {
    @Test
    fun `marks in progress before stopping and clears it after`() =
        runTest {
            val events = mutableListOf<String>()

            runIdleStopSequence(
                markInProgress = { inProgress -> events += if (inProgress) "mark-true" else "mark-false" },
                stop = { events += "stop" },
                onStopped = { events += "stopped" },
            )

            assertEquals(listOf("mark-true", "stop", "mark-false", "stopped"), events)
        }

    @Test
    fun `clears the in-progress flag even when stop throws`() =
        runTest {
            val events = mutableListOf<String>()

            val thrown =
                runCatching {
                    runIdleStopSequence(
                        markInProgress = { inProgress -> events += if (inProgress) "mark-true" else "mark-false" },
                        stop = { error("stop failed") },
                        onStopped = { events += "stopped" },
                    )
                }.exceptionOrNull()

            assertEquals("stop failed", thrown?.message)
            // onStopped still runs from the same finally as the clear - a throwing stop must not leave
            // an unmonitored foreground service any more than it may leave the flag stuck.
            assertEquals(listOf("mark-true", "mark-false", "stopped"), events)
        }

    /**
     * This is the actual regression: when the coroutine this sequence runs in never gets to start -
     * because the scope carrying it was already cancelled, exactly like `onDestroy`'s
     * `scope.cancel()` racing `checkIdleStop`'s `scope.launch` - neither the set nor the stop nor the
     * clear runs. There is no window where `markInProgress(true)` fires without a matching `false`,
     * because the two are no longer split across the launch boundary.
     */
    @Test
    fun `a scope cancelled before the coroutine starts runs neither the mark nor the stop`() =
        runTest {
            val events = mutableListOf<String>()
            val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
            scope.cancel()

            scope.launch {
                runIdleStopSequence(
                    markInProgress = { inProgress -> events += if (inProgress) "mark-true" else "mark-false" },
                    stop = { events += "stop" },
                    onStopped = { events += "stopped" },
                )
            }
            testScheduler.runCurrent()

            assertTrue(events.isEmpty())
        }

    /**
     * Interleaving 1: the watchdog decided to stop, then a foreground return arrives before the stop
     * coroutine runs. The in-progress flag is set synchronously with the decision, so the real
     * restore predicate treats the return as needing a restore.
     */
    @Test
    fun `a foreground return between the idle decision and the stop still restores`() {
        assertTrue(
            shouldRestoreOnForegroundReturn(
                status = LocalRuntimeStatus.Ready("1.18.5", 4098),
                idleStopInProgress = true,
                userStoppedRuntime = false,
            ),
        )
    }

    /**
     * Interleaving 2: a start lands while the idle stop waits for the manager lock. The real
     * still-applies decision sees the newer command as superseding, so the stop is declined.
     */
    @Test
    fun `a start that lands during the idle stop supersedes it`() {
        assertFalse(
            idleStopStillApplies(
                idleStopRequested = false,
                status = LocalRuntimeStatus.Ready("1.18.5", 4098),
                activeTags = setOf(IDLE_STOP_LEASE_TAG),
                appInForeground = false,
                adbConnected = false,
            ),
        )
    }

    /** The uncontested case still goes ahead: nothing newer arrived and the runtime is idle. */
    @Test
    fun `an uncontested idle stop still applies`() {
        assertTrue(
            idleStopStillApplies(
                idleStopRequested = true,
                status = LocalRuntimeStatus.Ready("1.18.5", 4098),
                activeTags = setOf(IDLE_STOP_LEASE_TAG),
                appInForeground = false,
                adbConnected = false,
            ),
        )
    }

    /**
     * A cancellation landing mid-stop still has to clear the flag: the alternative is a service
     * that went away with `idleStopInProgress` stuck true, which makes every later foreground
     * return send ACTION_START at a runtime that never needed restoring.
     */
    @Test
    fun `a scope cancelled while stopping still clears what it set`() =
        runTest {
            val events = mutableListOf<String>()
            val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))

            scope.launch {
                runIdleStopSequence(
                    markInProgress = { inProgress -> events += if (inProgress) "mark-true" else "mark-false" },
                    stop = {
                        events += "stop"
                        scope.cancel()
                    },
                    onStopped = { events += "stopped" },
                )
            }
            testScheduler.runCurrent()

            assertEquals(listOf("mark-true", "stop", "mark-false", "stopped"), events)
        }
}
