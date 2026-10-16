package com.yugahashimoto.andcode.runtime.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

class StartupCommandsRunnerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val launches = mutableListOf<RecordedLaunch>()

    /** Returns the process for a launch, or null to simulate a spawn failure. */
    private var nextProcess: (RecordedLaunch) -> Process? = { FakeProcess() }

    private lateinit var runtimeDirectory: File

    private lateinit var workspaceDirectory: File

    private fun setUpRunner(githubToken: String? = null): StartupCommandsRunner {
        runtimeDirectory = temporaryFolder.newFolder("runtime")
        workspaceDirectory = temporaryFolder.newFolder("workspace")
        return StartupCommandsRunner(
            runtimeDirectory = runtimeDirectory,
            workspaceDirectory = workspaceDirectory,
            githubToken = { githubToken },
            processStarter = { command, environment, logFile ->
                val launch = RecordedLaunch(command, environment, logFile)
                launches += launch
                nextProcess(launch) ?: error("simulated spawn failure")
            },
        )
    }

    private fun writeConfig(json: String) {
        File(workspaceDirectory, ".and-code").mkdirs()
        File(workspaceDirectory, ".and-code/startup-commands.json").writeText(json)
    }

    @Test
    fun `missing config runs nothing`() {
        val runner = setUpRunner()

        runner.runAll(installedRuntime())

        assertTrue(launches.isEmpty())
    }

    @Test
    fun `commands run detached in the guest shell at the workspace`() {
        val runner = setUpRunner()
        writeConfig(
            """{"commands":[{"command":"npx @wonderwhy-er/desktop-commander@latest remote"},""" +
                """{"command":"python3 -m http.server 8000"}]}""",
        )

        runner.runAll(installedRuntime())

        assertEquals(2, launches.size)
        val first = launches[0]
        assertEquals("/suite-proot", first.command.first())
        assertTrue("rootfs is the installed one", first.command.contains("/runtime-rootfs"))
        assertTrue(
            "workspace is bound into the guest",
            first.command.contains("${workspaceDirectory.absolutePath}:/workspace"),
        )
        assertEquals("-w", first.command[first.command.size - 5])
        assertEquals("/workspace", first.command[first.command.size - 4])
        assertEquals("/bin/sh", first.command[first.command.size - 3])
        assertEquals("-lc", first.command[first.command.size - 2])
        assertEquals("npx @wonderwhy-er/desktop-commander@latest remote", first.command.last())
        assertEquals(
            File(runtimeDirectory, "logs/startup-command-0.log").absolutePath,
            first.logFile.absolutePath,
        )
        assertEquals(
            File(runtimeDirectory, "logs/startup-command-1.log").absolutePath,
            launches[1].logFile.absolutePath,
        )
    }

    @Test
    fun `guest environment is the shared local runtime environment`() {
        val runner = setUpRunner(githubToken = "token-from-encrypted-store")
        writeConfig("""{"commands":[{"command":"echo hi"}]}""")

        runner.runAll(installedRuntime())

        val environment = launches.single().environment
        assertEquals("/root", environment["HOME"])
        assertEquals("token-from-encrypted-store", environment["OPENCODE_GITHUB_TOKEN"])
        assertEquals(
            File(runtimeDirectory, "proot-tmp").absolutePath,
            environment["PROOT_TMP_DIR"],
        )
    }

    @Test
    fun `check command exiting zero skips its command`() {
        val runner = setUpRunner()
        writeConfig(
            """{"commands":[{"command":"npx desktop-commander remote","checkCommand":"pgrep -f desktop-commander"},""" +
                """{"command":"python3 -m http.server 8000"}]}""",
        )
        nextProcess = { launch ->
            if (launch.command.last() == "pgrep -f desktop-commander") FakeProcess(exitCode = 0) else FakeProcess()
        }

        runner.runAll(installedRuntime())

        assertFalse(
            "the guarded command must not run",
            launches.any { it.command.last() == "npx desktop-commander remote" },
        )
        assertEquals(
            "the unguarded entry still runs",
            listOf("pgrep -f desktop-commander", "python3 -m http.server 8000"),
            launches.map { it.command.last() },
        )
    }

    @Test
    fun `check command failing runs the command anyway`() {
        val runner = setUpRunner()
        writeConfig("""{"commands":[{"command":"npx desktop-commander remote","checkCommand":"pgrep -f desktop-commander"}]}""")
        nextProcess = { launch ->
            if (launch.command.last() == "pgrep -f desktop-commander") FakeProcess(exitCode = 1) else FakeProcess()
        }

        runner.runAll(installedRuntime())

        assertEquals(
            listOf("pgrep -f desktop-commander", "npx desktop-commander remote"),
            launches.map { it.command.last() },
        )
    }

    @Test
    fun `check command that hangs is killed and its command still runs`() {
        val runner = setUpRunner()
        writeConfig("""{"commands":[{"command":"npx desktop-commander remote","checkCommand":"pgrep -f desktop-commander"}]}""")
        var killed = false
        nextProcess = { launch ->
            if (launch.command.last() == "pgrep -f desktop-commander") {
                FakeProcess(exitCode = 0, exitsInTime = false, onDestroyed = { killed = true })
            } else {
                FakeProcess()
            }
        }

        runner.runAll(installedRuntime())

        assertTrue("the hung probe must be killed", killed)
        assertEquals("npx desktop-commander remote", launches.last().command.last())
    }

    @Test
    fun `blank check command is treated as no check and runs the command`() {
        val runner = setUpRunner()
        writeConfig("""{"commands":[{"command":"npx desktop-commander remote","checkCommand":"   "}]}""")

        runner.runAll(installedRuntime())

        // An empty-shell probe would always exit 0 and silently skip the guarded command.
        assertEquals(listOf("npx desktop-commander remote"), launches.map { it.command.last() })
    }

    @Test
    fun `malformed config runs nothing and never throws`() {
        val runner = setUpRunner()
        writeConfig("""{"commands": [ {"command": """")

        runner.runAll(installedRuntime())

        assertTrue(launches.isEmpty())
    }

    @Test
    fun `blank command entries are skipped`() {
        val runner = setUpRunner()
        writeConfig("""{"commands":[{"command":"   "},{"command":"python3 -m http.server 8000"}]}""")

        runner.runAll(installedRuntime())

        assertEquals(1, launches.size)
        assertEquals(
            "the surviving entry keeps its own log slot",
            File(runtimeDirectory, "logs/startup-command-1.log").absolutePath,
            launches.single().logFile.absolutePath,
        )
    }

    @Test
    fun `one entry failing to spawn does not stop the others`() {
        val runner = setUpRunner()
        writeConfig("""{"commands":[{"command":"will-not-spawn"},{"command":"python3 -m http.server 8000"}]}""")
        nextProcess = { launch ->
            if (launch.command.last() == "will-not-spawn") null else FakeProcess()
        }

        runner.runAll(installedRuntime())

        assertEquals(
            listOf("will-not-spawn", "python3 -m http.server 8000"),
            launches.map { it.command.last() },
        )
    }

    @Test
    fun `detached processes are not waited on`() {
        val runner = setUpRunner()
        writeConfig("""{"commands":[{"command":"python3 -m http.server 8000"}]}""")
        var waited = false
        nextProcess = { FakeProcess(onWaitFor = { waited = true }) }

        runner.runAll(installedRuntime())

        assertFalse("a detached service must never block the caller", waited)
    }

    private fun installedRuntime(): LocalRuntimeInstaller.InstalledRuntime =
        LocalRuntimeInstaller.InstalledRuntime(
            metadata = LocalRuntimeMetadata(version = "1.17.20", port = 4096, installedAt = 123),
            commandSuite =
                EmbeddedCommandSuite.Paths(
                    home = File("/suite-home"),
                    tmp = File("/suite-tmp"),
                    nativeLibraryDirectory = File("/suite-native"),
                    proot = File("/suite-proot"),
                    loader = File("/suite-loader"),
                    loader32 = File("/suite-loader32"),
                ),
            rootfs = File("/runtime-rootfs"),
            openCode = null,
        )

    private data class RecordedLaunch(
        val command: List<String>,
        val environment: Map<String, String>,
        val logFile: File,
    )

    private class FakeProcess(
        private val exitCode: Int = 0,
        private val exitsInTime: Boolean = true,
        private val onDestroyed: () -> Unit = {},
        private val onWaitFor: () -> Unit = {},
    ) : Process() {
        override fun getOutputStream(): OutputStream = error("unused")

        override fun getInputStream(): InputStream = error("unused")

        override fun getErrorStream(): InputStream = error("unused")

        override fun waitFor(): Int = exitCode

        override fun waitFor(
            timeout: Long,
            unit: TimeUnit,
        ): Boolean {
            onWaitFor()
            return exitsInTime
        }

        override fun exitValue(): Int = exitCode

        override fun destroy() {
            onDestroyed()
        }

        override fun destroyForcibly(): Process = apply { onDestroyed() }
    }
}
