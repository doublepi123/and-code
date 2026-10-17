package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.runtime.LocalRuntimeStatus
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LocalRuntimePortTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    private fun writeMetadata(port: Int) {
        File(temporaryFolder.root, "metadata.json").writeText(
            """{"version":"1.18.5","port":$port,"installedAt":123}""",
        )
    }

    private fun readPort(): Int = json.decodeFromString<LocalRuntimeMetadata>(File(temporaryFolder.root, "metadata.json").readText()).port

    @Test
    fun `a legacy 4097 install is moved to the manifest port`() {
        writeMetadata(LEGACY_LOCAL_RUNTIME_PORT)

        val migrated = migrateLegacyRuntimePort(runtimeDirectory = temporaryFolder.root, targetPort = { 4098 }, json = json)

        assertTrue(migrated)
        assertEquals(4098, readPort())
    }

    /** Only the exact legacy value is rewritten; a port someone else chose stays put. */
    @Test
    fun `a non-legacy port is left alone`() {
        writeMetadata(5555)

        assertFalse(migrateLegacyRuntimePort(runtimeDirectory = temporaryFolder.root, targetPort = { 4098 }, json = json))
        assertEquals(5555, readPort())
    }

    /** The manifest is only consulted when there is something to migrate. */
    @Test
    fun `the manifest port is not read when no migration is needed`() {
        writeMetadata(4098)
        var consulted = false

        migrateLegacyRuntimePort(
            runtimeDirectory = temporaryFolder.root,
            targetPort = {
                consulted = true
                4098
            },
            json = json,
        )

        assertFalse(consulted)
    }

    @Test
    fun `missing metadata is a no-op`() {
        assertFalse(migrateLegacyRuntimePort(runtimeDirectory = temporaryFolder.root, targetPort = { 4098 }, json = json))
    }

    @Test
    fun `the port-in-use reason names the port`() {
        assertEquals("Port 4098 is in use by another process", LocalRuntimeMessages.portInUse(4098))
    }

    /**
     * A port that is open but answered by a foreign server must not be reported Ready, and the
     * launcher must not be invoked. Driven through [LocalRuntimeManager.startInstalled] with an
     * ownership probe that says "not ours".
     */
    @Test
    fun `a foreign server on the port is reported broken and not treated as ready`() =
        runBlocking {
            val rootfs = temporaryFolder.newFolder("rootfs-foreign")
            val manager =
                LocalRuntimeManager(
                    runtimeDirectory = temporaryFolder.root,
                    abi = "arm64-v8a",
                    portProbe = { true },
                    ownershipProbe = { _, _ -> false },
                    managedRuntimeRunning = { false },
                    processLauncher = LocalRuntimeProcessLauncher(temporaryFolder.root, { true }),
                )

            try {
                manager.startInstalled(installedRuntime(rootfs, 4098))
                fail("expected the foreign port to be refused")
            } catch (expected: IllegalStateException) {
                assertEquals("Port 4098 is in use by another process", expected.message)
            }
            assertEquals(
                LocalRuntimeStatus.Broken("Port 4098 is in use by another process"),
                manager.state.value,
            )
        }

    /**
     * A proot we started is already on the port: it is ours, so the HTTP probe must not run. This
     * is the slow-to-answer case the probe would otherwise misread as a foreign server.
     */
    @Test
    fun `a managed runtime already running is trusted without probing the port owner`() =
        runBlocking {
            val rootfs = temporaryFolder.newFolder("rootfs-owned")
            var probed = false
            val manager =
                LocalRuntimeManager(
                    runtimeDirectory = temporaryFolder.root,
                    abi = "arm64-v8a",
                    portProbe = { true },
                    ownershipProbe = { _, _ ->
                        probed = true
                        false
                    },
                    managedRuntimeRunning = { true },
                    processLauncher = LocalRuntimeProcessLauncher(temporaryFolder.root, { true }),
                    systemPrompt = { null },
                )

            val ready = manager.startInstalled(installedRuntime(rootfs, 4098))

            assertFalse(probed)
            assertEquals("1.18.5", ready.version)
            assertEquals(4098, ready.port)
        }

    private fun installedRuntime(
        rootfs: File,
        port: Int,
    ): LocalRuntimeInstaller.InstalledRuntime =
        LocalRuntimeInstaller.InstalledRuntime(
            metadata = LocalRuntimeMetadata(version = "1.18.5", port = port, installedAt = 123),
            commandSuite =
                EmbeddedCommandSuite.Paths(
                    home = rootfs,
                    tmp = rootfs,
                    nativeLibraryDirectory = rootfs,
                    proot = rootfs,
                    loader = rootfs,
                    loader32 = rootfs,
                ),
            rootfs = rootfs,
            openCode = null,
        )
}
