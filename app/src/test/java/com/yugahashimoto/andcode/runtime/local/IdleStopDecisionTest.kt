package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.runtime.LocalRuntimeStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The pure "is the idle stop still wanted" decision, plus [LocalRuntimeManager.stopIfStillWanted]
 * run against a real manager with a stubbed launcher, so the stop-or-leave outcome is observed on
 * the runtime's state rather than re-derived in the test.
 */
class IdleStopDecisionTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun applies(
        requested: Boolean = true,
        status: LocalRuntimeStatus = LocalRuntimeStatus.Ready("1.18.5", 4098),
        tags: Set<String> = setOf(IDLE_STOP_LEASE_TAG),
        foreground: Boolean = false,
        adb: Boolean = false,
    ): Boolean =
        idleStopStillApplies(
            idleStopRequested = requested,
            status = status,
            activeTags = tags,
            appInForeground = foreground,
            adbConnected = adb,
        )

    /** The stop's own lease must not block the stop it belongs to. */
    @Test
    fun `its own idle-stop lease alone does not block the stop`() {
        assertTrue(applies(tags = setOf(IDLE_STOP_LEASE_TAG)))
    }

    /** Real work in another tag does block the stop, even alongside the idle-stop lease. */
    @Test
    fun `other work alongside the idle-stop lease blocks the stop`() {
        assertFalse(applies(tags = setOf(IDLE_STOP_LEASE_TAG, "sessions")))
        assertFalse(applies(tags = setOf("runtime-op")))
    }

    @Test
    fun `a superseded decision does not apply`() {
        assertFalse(applies(requested = false))
    }

    @Test
    fun `a runtime that is no longer Ready does not apply`() {
        assertFalse(applies(status = LocalRuntimeStatus.Starting("1.18.5", 4098)))
        assertFalse(applies(status = LocalRuntimeStatus.Stopped("1.18.5", 4098)))
    }

    @Test
    fun `foreground or an attached adb link does not apply`() {
        assertFalse(applies(foreground = true))
        assertFalse(applies(adb = true))
    }

    @Test
    fun `an uncontested idle runtime applies`() {
        assertTrue(applies())
    }

    private fun managerWith(portProbe: (Int) -> Boolean): LocalRuntimeManager {
        val launcher =
            LocalRuntimeProcessLauncher(
                runtimeDirectory = temporaryFolder.root,
                portProbe = portProbe,
                procRoot = temporaryFolder.newFolder("proc"),
            )
        return LocalRuntimeManager(
            runtimeDirectory = temporaryFolder.root,
            abi = "arm64-v8a",
            portProbe = portProbe,
            processLauncher = launcher,
        )
    }

    private fun writeMetadata() {
        File(temporaryFolder.root, "metadata.json").writeText("""{"version":"1.18.5","port":4098,"installedAt":123}""")
    }

    /** A declined stop leaves the runtime untouched: no Stopped state is published. */
    @Test
    fun `stopIfStillWanted returns false and leaves the runtime alone when no longer wanted`() =
        runBlocking {
            writeMetadata()
            val manager = managerWith { true }
            val before = manager.state.value

            val stopped = manager.stopIfStillWanted { false }

            assertFalse(stopped)
            assertEquals(before, manager.state.value)
        }

    /** A wanted stop goes ahead and publishes Stopped with the recorded version and port. */
    @Test
    fun `stopIfStillWanted stops the runtime when still wanted`() =
        runBlocking {
            writeMetadata()
            val manager = managerWith { true }

            val stopped = manager.stopIfStillWanted { true }

            assertTrue(stopped)
            assertEquals(LocalRuntimeStatus.Stopped("1.18.5", 4098), manager.state.value)
        }
}
