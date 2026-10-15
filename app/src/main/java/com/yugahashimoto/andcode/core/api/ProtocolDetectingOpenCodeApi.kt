package com.yugahashimoto.andcode.core.api

import com.yugahashimoto.andcode.core.security.OpenCodeUrl
import com.yugahashimoto.andcode.data.connection.ConnectionProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/** Which HTTP API a server speaks. V2 servers answer under the `api/` prefix; V1 servers answer the legacy routes. */
enum class OpenCodeProtocol { V1, V2 }

/**
 * Routes every [OpenCodeApi] call to the V1 or V2 client, detected from the server itself.
 *
 * Detection happens in [health], which always re-probes so that connecting to a server that was
 * upgraded (or replaced) picks up the new protocol. Other calls reuse the last answer and probe only
 * when nothing is known yet. A probe that fails at the transport level is rethrown and not cached,
 * so a flaky network cannot pin the wrong protocol for the rest of the session.
 */
class ProtocolDetectingOpenCodeApi(
    private val profile: ConnectionProfile,
    private val httpClient: OkHttpClient = OpenCodeApiClient.defaultHttpClient(profile),
    private val json: Json = OpenCodeApiClient.defaultJson,
) : OpenCodeApi {
    // Resolved lazily so that constructing the detector never throws, matching the V1 client.
    private val baseUrl: HttpUrl by lazy { OpenCodeUrl.normalize(profile.baseUrl).getOrThrow() }

    private val v1: OpenCodeApiClient = OpenCodeApiClient(profile, httpClient, json)
    private val v2: OpenCodeV2ApiClient = OpenCodeV2ApiClient(profile, httpClient, json)

    private val detectedState = MutableStateFlow<OpenCodeProtocol?>(null)

    /** Serialises probes so a slower, older probe cannot overwrite the result of a newer one. */
    private val probeLock = Mutex()

    /** The protocol the last probe found, or null before the first probe. Read-only for capability gating. */
    val protocol: OpenCodeProtocol?
        get() = detectedState.value

    /** Emits the protocol whenever a probe changes it, so event streams can follow an upgrade. */
    private val protocolFlow: StateFlow<OpenCodeProtocol?> = detectedState.asStateFlow()

    override suspend fun health(): OpenCodeHealth {
        // Health always re-probes: connecting is the moment a server upgrade should be noticed.
        return when (val probe = probeAndRemember()) {
            is Probe.V2 -> OpenCodeHealth(healthy = true, version = probe.version)
            // The V2 auth failure is reported as a 401, the same as the V1 client reports one.
            Probe.V2Unauthorized -> throw OpenCodeApiException(statusCode = 401, message = "OpenCode request failed (HTTP 401)")
            // The V1 verdict already came from `global/health`; its body is the health, so it is not fetched twice.
            is Probe.Legacy -> probe.health
        }
    }

    override suspend fun sessions(directory: String?): List<OpenCodeSession> = current().sessions(directory)

    override suspend fun session(sessionId: String): OpenCodeSession = current().session(sessionId)

    override suspend fun createSession(
        title: String?,
        directory: String?,
    ): OpenCodeSession = current().createSession(title, directory)

    override suspend fun messages(sessionId: String): List<OpenCodeMessage> = current().messages(sessionId)

    override suspend fun deleteMessage(
        sessionId: String,
        messageId: String,
    ): Boolean = current().deleteMessage(sessionId, messageId)

    override suspend fun providers(): ProviderCatalog = current().providers()

    override suspend fun agents(): List<OpenCodeAgent> = current().agents()

    override suspend fun providerAuthMethods(): Map<String, List<ProviderAuthMethod>> = current().providerAuthMethods()

    override suspend fun authorizeProvider(
        providerId: String,
        methodIndex: Int,
        inputs: Map<String, String>,
    ): ProviderAuthAuthorization = current().authorizeProvider(providerId, methodIndex, inputs)

    override suspend fun setProviderApiKey(
        providerId: String,
        apiKey: String,
        metadata: Map<String, String>,
    ): Boolean = current().setProviderApiKey(providerId, apiKey, metadata)

    override suspend fun removeProviderAuth(providerId: String): Boolean = current().removeProviderAuth(providerId)

    override suspend fun completeProviderOAuth(
        providerId: String,
        methodIndex: Int,
        code: String?,
    ): Boolean = current().completeProviderOAuth(providerId, methodIndex, code)

    override suspend fun projects(directory: String?): List<OpenCodeProject> = current().projects(directory)

    override suspend fun currentProject(directory: String?): OpenCodeProject = current().currentProject(directory)

    override suspend fun pathInfo(directory: String?): OpenCodePathInfo = current().pathInfo(directory)

    override suspend fun files(
        directory: String,
        path: String,
    ): List<OpenCodeFileNode> = current().files(directory, path)

    override suspend fun fileContent(
        directory: String,
        path: String,
    ): OpenCodeFileContent = current().fileContent(directory, path)

    override suspend fun fileStatus(directory: String): List<OpenCodeFileChange> = current().fileStatus(directory)

    override suspend fun searchText(
        directory: String,
        pattern: String,
    ): List<OpenCodeSearchMatch> = current().searchText(directory, pattern)

    override suspend fun findFiles(
        directory: String,
        queryText: String,
        includeDirectories: Boolean?,
        type: String?,
        limit: Int?,
    ): List<String> = current().findFiles(directory, queryText, includeDirectories, type, limit)

    override suspend fun vcsInfo(directory: String): OpenCodeVcsInfo = current().vcsInfo(directory)

    override suspend fun vcsStatus(directory: String): List<OpenCodeFileChange> = current().vcsStatus(directory)

    override suspend fun vcsDiff(
        directory: String,
        mode: String,
        context: Int?,
    ): List<OpenCodeFileChange> = current().vcsDiff(directory, mode, context)

    override suspend fun sessionDiff(
        sessionId: String,
        directory: String?,
        messageId: String?,
    ): List<OpenCodeFileChange> = current().sessionDiff(sessionId, directory, messageId)

    override suspend fun sessionTodo(
        sessionId: String,
        directory: String?,
    ): List<OpenCodeTodo> = current().sessionTodo(sessionId, directory)

    override suspend fun promptAsync(
        sessionId: String,
        request: PromptRequest,
    ) = current().promptAsync(sessionId, request)

    override suspend fun summarizeSession(
        sessionId: String,
        providerId: String,
        modelId: String,
    ): Boolean = current().summarizeSession(sessionId, providerId, modelId)

    override suspend fun abortSession(sessionId: String): Boolean = current().abortSession(sessionId)

    override suspend fun renameSession(
        sessionId: String,
        title: String,
        directory: String?,
    ): OpenCodeSession = current().renameSession(sessionId, title, directory)

    override suspend fun deleteSession(
        sessionId: String,
        directory: String?,
    ): Boolean = current().deleteSession(sessionId, directory)

    override suspend fun archiveSession(
        sessionId: String,
        directory: String?,
    ): OpenCodeSession = current().archiveSession(sessionId, directory)

    override suspend fun mcpServers(): List<McpServer> = current().mcpServers()

    override suspend fun addMcpServer(body: JsonObject): McpServer = current().addMcpServer(body)

    override suspend fun connectMcpServer(name: String): Boolean = current().connectMcpServer(name)

    override suspend fun disconnectMcpServer(name: String): Boolean = current().disconnectMcpServer(name)

    override suspend fun removeMcpAuth(name: String): McpAuthRemoval = current().removeMcpAuth(name)

    override suspend fun mcpAuth(name: String): McpAuthStart = current().mcpAuth(name)

    override suspend fun mcpAuthCallback(
        name: String,
        code: String,
    ): McpAuthStatus = current().mcpAuthCallback(name, code)

    override suspend fun config(): kotlinx.serialization.json.JsonElement = current().config()

    override suspend fun updateConfig(patch: JsonObject): kotlinx.serialization.json.JsonElement = current().updateConfig(patch)

    override suspend fun configProviders(): List<ConfiguredProvider> = current().configProviders()

    override suspend fun commands(): List<OpenCodeCommand> = current().commands()

    override suspend fun skills(): List<OpenCodeSkill> = current().skills()

    override suspend fun executeCommand(
        sessionId: String,
        command: String,
        arguments: String,
        agent: String?,
        model: String?,
        variant: String?,
    ) = current().executeCommand(sessionId, command, arguments, agent, model, variant)

    override suspend fun respondPermission(
        sessionId: String,
        permissionId: String,
        response: String,
        remember: Boolean,
    ): Boolean = current().respondPermission(sessionId, permissionId, response, remember)

    override suspend fun answerQuestion(
        requestId: String,
        answers: List<List<String>>,
        directory: String?,
    ): Boolean = current().answerQuestion(requestId, answers, directory)

    override suspend fun rejectQuestion(
        requestId: String,
        directory: String?,
    ): Boolean = current().rejectQuestion(requestId, directory)

    override suspend fun pendingQuestions(directory: String?): List<QuestionRequest> = current().pendingQuestions(directory)

    /**
     * Event streams follow the protocol: when a probe changes it (a V1 server upgraded to V2, or
     * the reverse), the running stream is cancelled and restarted on the new client. Without this a
     * V1 stream kept retrying `/global/event` forever against a server that no longer speaks V1.
     */
    override fun events(): Flow<OpenCodeEvent> =
        flow {
            if (detectedState.value == null) probeAndRemember()
            emitAll(
                protocolFlow.filterNotNull().distinctUntilChanged().flatMapLatest { protocol ->
                    api(protocol).events()
                },
            )
        }

    /** Uses the known protocol, or probes once when none is known yet. */
    private suspend fun current(): OpenCodeApi {
        val known = detectedState.value ?: probeAndRemember().protocolOrV1()
        return api(known)
    }

    private fun api(protocol: OpenCodeProtocol): OpenCodeApi =
        when (protocol) {
            OpenCodeProtocol.V1 -> v1
            OpenCodeProtocol.V2 -> v2
        }

    /**
     * Probes under [probeLock] and publishes the result. Only a conclusive answer is published; a
     * transient failure (network, 5xx, 408, 429) is thrown and leaves the previous answer intact.
     */
    private suspend fun probeAndRemember(): Probe =
        probeLock.withLock {
            val probe = probe()
            detectedState.value = probe.protocolOrV1()
            probe
        }

    private fun Probe.protocolOrV1(): OpenCodeProtocol =
        when (this) {
            is Probe.V2, Probe.V2Unauthorized -> OpenCodeProtocol.V2
            is Probe.Legacy -> OpenCodeProtocol.V1
        }

    /**
     * Decides the protocol. A JSON body with a string `version` on `GET api/info` is the V2
     * signature, and so is a 401 whose body carries the V2 `UnauthorizedError` tag: the server is
     * V2 and only the credentials are wrong.
     *
     * V1 servers answer `api/info` with the single-page-app HTML, which is not conclusive by itself.
     * V1 is only confirmed when `global/health` succeeds, so an outage or proxy error cannot be
     * mistaken for a V1 server. Transient statuses (5xx, 408, 429) are thrown and not cached.
     */
    private suspend fun probe(): Probe =
        withContext(Dispatchers.IO) {
            val info = fetchStatus("api/info")
            when {
                info.code in 200..299 -> versionOf(info.body)?.let { Probe.V2(it) } ?: confirmLegacy()
                info.code == 401 && isUnauthorizedTag(info.body) -> Probe.V2Unauthorized
                info.code == 401 || info.code == 403 || info.code == 404 || info.code in 300..399 -> confirmLegacy()
                else -> throw OpenCodeApiException(statusCode = info.code, message = "OpenCode probe failed (HTTP ${info.code})")
            }
        }

    /**
     * Confirms a V1 server with `global/health`; any non-success is not a V1 verdict. The body is
     * kept so that [health] can return it without a second request.
     */
    private fun confirmLegacy(): Probe {
        // A V1 server may run with a custom OPENCODE_SERVER_USERNAME, which the V1 client honours,
        // so the V1 confirmation authenticates as the profile does rather than as V2's fixed user.
        val response = fetchStatus("global/health", username = profile.username.ifBlank { OpenCodeV2ApiClient.USERNAME })
        if (response.code !in 200..299) {
            throw OpenCodeApiException(statusCode = response.code, message = "OpenCode probe failed (HTTP ${response.code})")
        }
        val health =
            runCatching { json.decodeFromString<OpenCodeHealth>(response.body) }
                .getOrElse { throw OpenCodeApiException(statusCode = 200, message = "OpenCode V1 health response was not JSON") }
        return Probe.Legacy(health)
    }

    /**
     * [username] defaults to V2's fixed `opencode` user: V2 rejects any other name, so `api/info`
     * must use it even when the profile carries a V1 custom username.
     */
    private fun fetchStatus(
        path: String,
        username: String = OpenCodeV2ApiClient.USERNAME,
    ): ProbeResponse {
        val url = baseUrl.resolve(path) ?: throw IllegalArgumentException("Invalid OpenCode API path")
        val request =
            Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .apply {
                    profile.password?.takeIf { it.isNotBlank() }?.let { password ->
                        header("Authorization", Credentials.basic(username, password))
                    }
                }
                .build()
        httpClient.newCall(request).execute().use { response ->
            return ProbeResponse(response.code, response.body?.string().orEmpty())
        }
    }

    private class ProbeResponse(val code: Int, val body: String)

    private fun versionOf(body: String): String? =
        runCatching {
            (probeJson.parseToJsonElement(body) as? JsonObject)
                ?.get("version")
                ?.let { (it as? JsonPrimitive)?.takeIf { primitive -> primitive.isString }?.content }
        }.getOrNull()

    private fun isUnauthorizedTag(body: String): Boolean =
        runCatching {
            (probeJson.parseToJsonElement(body) as? JsonObject)?.get("_tag")?.let { tag ->
                (tag as? JsonPrimitive)?.content == UNAUTHORIZED_TAG
            } == true
        }.getOrDefault(false)

    private sealed interface Probe {
        data class V2(val version: String) : Probe

        data object V2Unauthorized : Probe

        data class Legacy(val health: OpenCodeHealth) : Probe
    }

    private companion object {
        const val UNAUTHORIZED_TAG = "UnauthorizedError"

        /** Strict parsing, so an HTML page is never accepted as JSON by the lenient default. */
        val probeJson: Json = Json { ignoreUnknownKeys = true }
    }
}
