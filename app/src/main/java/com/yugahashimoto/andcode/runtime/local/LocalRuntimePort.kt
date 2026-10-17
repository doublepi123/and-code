package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.core.api.OpenCodeHealth
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

/**
 * The port the runtime listened on before this fork moved to its own port. The port is copied into
 * the metadata at install time, so an existing install keeps it until [migrateLegacyRuntimePort].
 */
internal const val LEGACY_LOCAL_RUNTIME_PORT = 4097

private const val METADATA_FILE_NAME = "metadata.json"
private const val OWNERSHIP_TIMEOUT_MILLIS = 2_000
private const val HTTP_OK = 200
private val OWNERSHIP_JSON =
    Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

/**
 * Moves an install recorded on [LEGACY_LOCAL_RUNTIME_PORT] to the manifest's port. Only that exact
 * legacy value is rewritten, so any other port is left alone. The target is read lazily so a
 * metadata file that needs no migration never touches the manifest asset. Returns true when the
 * metadata was rewritten.
 */
internal fun migrateLegacyRuntimePort(
    runtimeDirectory: File,
    targetPort: () -> Int,
    json: Json,
): Boolean {
    val metadataFile = File(runtimeDirectory, METADATA_FILE_NAME)
    if (!metadataFile.isFile) return false
    val metadata =
        runCatching { json.decodeFromString<LocalRuntimeMetadata>(metadataFile.readText()) }.getOrNull()
            ?: return false
    if (metadata.port != LEGACY_LOCAL_RUNTIME_PORT) return false
    val port = targetPort()
    if (port == LEGACY_LOCAL_RUNTIME_PORT || port !in 1..65535) return false
    val staged = File(runtimeDirectory, "$METADATA_FILE_NAME.port-migration")
    try {
        staged.writeText(json.encodeToString(metadata.copy(port = port)))
        replaceFileAtomically(staged, metadataFile)
    } finally {
        staged.delete()
    }
    return true
}

/**
 * Whether the server answering on [port] is our OpenCode, judged by an authenticated
 * `global/health` request. A rejected password, a non-OpenCode answer, a refused or timed-out
 * connection all count as not ours. Blocking: call it off the main thread.
 */
internal fun defaultServerOwnershipProbe(
    port: Int,
    password: String,
): Boolean =
    runCatching {
        val connection = URL("http://127.0.0.1:$port/global/health").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = OWNERSHIP_TIMEOUT_MILLIS
            connection.readTimeout = OWNERSHIP_TIMEOUT_MILLIS
            connection.requestMethod = "GET"
            val credentials = "${LocalServerAuth.USERNAME}:$password".toByteArray(Charsets.UTF_8)
            connection.setRequestProperty("Authorization", "Basic " + Base64.getEncoder().encodeToString(credentials))
            if (connection.responseCode != HTTP_OK) {
                false
            } else {
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                OWNERSHIP_JSON.decodeFromString<OpenCodeHealth>(body).healthy
            }
        } finally {
            connection.disconnect()
        }
    }.getOrDefault(false)

/** The status reason shown when the local port is held by a server that is not ours. */
internal fun portInUseReason(port: Int): String = "Port $port is in use by another process"
