package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.core.storage.DeviceStorage
import java.io.File

/**
 * The PRoot argument list every guest process shares: the embedded binary, its isolation flags, the
 * rootfs, the fixed device binds, and - when [workspace] is given - the bind that mounts the guest
 * workspace at `/workspace`. Callers append the working directory and the guest executable plus its
 * arguments.
 */
internal fun prootCommandPrefix(
    commandSuite: EmbeddedCommandSuite.Paths,
    rootfs: File,
    workspace: File? = null,
): List<String> =
    buildList {
        add(commandSuite.proot.absolutePath)
        add("--kill-on-exit")
        add("--link2symlink")
        add("-0")
        add("-r")
        add(rootfs.absolutePath)
        add("-b")
        add("/dev")
        add("-b")
        add("/proc")
        add("-b")
        add("/sys")
        add("-b")
        add("/system")
        workspace?.let {
            add("-b")
            add("${it.absolutePath}:/workspace")
        }
        // Nothing while all-files access is ungranted, so the sandbox stays exactly as
        // narrow as it was until the user opens the device up to it.
        addAll(DeviceStorage.bindArguments())
    }
