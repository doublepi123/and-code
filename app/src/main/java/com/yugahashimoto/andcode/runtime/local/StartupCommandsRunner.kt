package com.yugahashimoto.andcode.runtime.local

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Launches guest-side startup commands declared in the workspace's
 * `.and-code/startup-commands.json` whenever the local runtime is (re)started (issue #379).
 *
 * The file is read on every [LocalRuntimeProcessLauncher.start]; each entry runs detached in its
 * own PRoot instance with `/workspace` as the working directory, so a long-running service neither
 * blocks the runtime start nor outlives it: the same managed-process sweep that stops the server
 * matches every PRoot rooted in this runtime directory and stops the services too.
 *
 * Schema (`command` is the only required field):
 * ```json
 * {
 *   "commands": [
 *     {
 *       "command": "npx @wonderwhy-er/desktop-commander@latest remote",
 *       "checkCommand": "pgrep -f desktop-commander"
 *     }
 *   ]
 * }
 * ```
 * A `checkCommand` that exits 0 within [checkTimeoutSeconds] skips its `command` - that is how an
 * entry opts into idempotent startup instead of racing a second copy of an already-running service.
 *
 * Best-effort by design: a missing or malformed config runs nothing, a spawn failure affects only
 * its own entry, and nothing here can delay or abort the runtime start. A command's output is
 * appended to `logs/startup-command-<index>.log`, truncated the same way the server log is.
 */
class StartupCommandsRunner(
    private val runtimeDirectory: File,
    private val workspaceDirectory: File = File(runtimeDirectory, "workspace"),
    private val githubToken: () -> String? = { null },
    private val checkTimeoutSeconds: Long = 10L,
    private val maxLogBytes: Long = 1_048_576L,
    /** Overridable so a JVM test can supply a fake [Process] instead of a real guest command. */
    private val processStarter: (
        command: List<String>,
        environment: Map<String, String>,
        logFile: File,
    ) -> Process = { command, environment, logFile ->
        ProcessBuilder(command)
            .directory(runtimeDirectory)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
            .apply {
                environment().clear()
                environment().putAll(environment)
            }
            .start()
    },
) {
    private val json: Json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    init {
        require(checkTimeoutSeconds > 0)
    }

    /**
     * Reads the config and launches the commands on a daemon thread. [schedule] runs on the
     * runtime start path, so it must return immediately and must never throw - hence the work
     * being off-thread and wrapped wholesale.
     */
    fun schedule(installed: LocalRuntimeInstaller.InstalledRuntime) {
        Thread({ runCatching { runAll(installed) } }, "startup-commands").apply { isDaemon = true }.start()
    }

    /** Synchronous for tests; [schedule] is the production entry point. */
    internal fun runAll(installed: LocalRuntimeInstaller.InstalledRuntime) {
        val config = readConfig() ?: return
        val logs = File(runtimeDirectory, "logs").apply { mkdirs() }
        config.commands.forEachIndexed { index, entry ->
            val commandText = entry.command.trim()
            if (commandText.isEmpty()) return@forEachIndexed
            // A blank checkCommand is "no check": probing `/bin/sh -lc ""` would always exit 0
            // and silently skip the command it guards.
            val checkCommand = entry.checkCommand?.takeIf(String::isNotBlank)
            if (checkCommand != null && alreadyRunning(installed, checkCommand)) {
                return@forEachIndexed
            }
            launchDetached(installed, commandText, File(logs, "$LOG_FILE_PREFIX-$index.log"))
        }
    }

    private fun readConfig(): StartupCommandsConfig? {
        val file = File(workspaceDirectory, CONFIG_FILE_RELATIVE_PATH)
        if (!file.isFile) return null
        return runCatching {
            json.decodeFromString<StartupCommandsConfig>(file.readText())
        }.getOrNull()
    }

    /** True when the entry's check command exits 0 in time, i.e. the service is already up. */
    private fun alreadyRunning(
        installed: LocalRuntimeInstaller.InstalledRuntime,
        checkCommand: String,
    ): Boolean {
        val logs = File(runtimeDirectory, "logs").apply { mkdirs() }
        val outputFile = File.createTempFile("startup-check-", ".log", logs)
        val probe =
            runCatching {
                processStarter(guestCommand(installed, checkCommand), guestEnvironment(installed), outputFile)
            }.getOrNull()
        if (probe == null) {
            outputFile.delete()
            return false
        }
        return try {
            val exited = probe.waitFor(checkTimeoutSeconds, TimeUnit.SECONDS)
            if (!exited) probe.destroyForcibly()
            exited && probe.exitValue() == 0
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            probe.destroyForcibly()
            false
        } finally {
            outputFile.delete()
        }
    }

    private fun launchDetached(
        installed: LocalRuntimeInstaller.InstalledRuntime,
        commandText: String,
        logFile: File,
    ) {
        truncateLogFile(logFile, maxLogBytes)
        // Deliberately not waited on and not tracked. The detached guest process is killed by the
        // managed-runtime sweep on stop, which matches every PRoot rooted in this runtime
        // directory, so there is no handle worth keeping.
        runCatching { processStarter(guestCommand(installed, commandText), guestEnvironment(installed), logFile) }
    }

    private fun guestCommand(
        installed: LocalRuntimeInstaller.InstalledRuntime,
        commandText: String,
    ): List<String> =
        prootCommandPrefix(installed.commandSuite, installed.rootfs, workspaceDirectory) +
            listOf(
                "-w",
                "/workspace",
                "/bin/sh",
                "-lc",
                commandText,
            )

    private fun guestEnvironment(installed: LocalRuntimeInstaller.InstalledRuntime): Map<String, String> =
        localRuntimeEnvironment(
            suiteEnvironment = installed.commandSuite.environment(),
            prootTmp = File(runtimeDirectory, "proot-tmp").apply { mkdirs() },
            githubToken = githubToken(),
        )

    private companion object {
        const val CONFIG_FILE_RELATIVE_PATH = ".and-code/startup-commands.json"
        const val LOG_FILE_PREFIX = "startup-command"
    }
}

@Serializable
private data class StartupCommandsConfig(
    @SerialName("commands") val commands: List<StartupCommandEntry> = emptyList(),
)

@Serializable
private data class StartupCommandEntry(
    @SerialName("command") val command: String = "",
    @SerialName("checkCommand") val checkCommand: String? = null,
)
