package com.yugahashimoto.andcode.core.api

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * The OpenCode server surface AndCode uses, independent of which wire protocol answers it.
 *
 * [OpenCodeApiClient] speaks the V1 REST/SSE API and [OpenCodeV2ApiClient] speaks V2; both map their
 * responses onto the same app DTOs so the backends and ViewModels above this line never branch on
 * the protocol. Default arguments live here because Kotlin does not allow an override to redeclare
 * them, and callers rely on the defaults through the interface type.
 */
interface OpenCodeApi {
    suspend fun health(): OpenCodeHealth

    suspend fun sessions(directory: String? = null): List<OpenCodeSession>

    suspend fun session(sessionId: String): OpenCodeSession

    suspend fun createSession(
        title: String? = null,
        directory: String? = null,
    ): OpenCodeSession

    suspend fun messages(sessionId: String): List<OpenCodeMessage>

    suspend fun deleteMessage(
        sessionId: String,
        messageId: String,
    ): Boolean

    suspend fun providers(): ProviderCatalog

    suspend fun agents(): List<OpenCodeAgent>

    suspend fun providerAuthMethods(): Map<String, List<ProviderAuthMethod>>

    suspend fun authorizeProvider(
        providerId: String,
        methodIndex: Int,
        inputs: Map<String, String> = emptyMap(),
    ): ProviderAuthAuthorization

    suspend fun setProviderApiKey(
        providerId: String,
        apiKey: String,
        metadata: Map<String, String> = emptyMap(),
    ): Boolean

    suspend fun removeProviderAuth(providerId: String): Boolean

    suspend fun completeProviderOAuth(
        providerId: String,
        methodIndex: Int,
        code: String?,
    ): Boolean

    suspend fun projects(directory: String? = null): List<OpenCodeProject>

    suspend fun currentProject(directory: String? = null): OpenCodeProject

    suspend fun pathInfo(directory: String? = null): OpenCodePathInfo

    suspend fun files(
        directory: String,
        path: String,
    ): List<OpenCodeFileNode>

    suspend fun fileContent(
        directory: String,
        path: String,
    ): OpenCodeFileContent

    suspend fun fileStatus(directory: String): List<OpenCodeFileChange>

    suspend fun searchText(
        directory: String,
        pattern: String,
    ): List<OpenCodeSearchMatch>

    suspend fun findFiles(
        directory: String,
        queryText: String,
        includeDirectories: Boolean? = null,
        type: String? = null,
        limit: Int? = null,
    ): List<String>

    suspend fun vcsInfo(directory: String): OpenCodeVcsInfo

    suspend fun vcsStatus(directory: String): List<OpenCodeFileChange>

    suspend fun vcsDiff(
        directory: String,
        mode: String = "git",
        context: Int? = null,
    ): List<OpenCodeFileChange>

    suspend fun sessionDiff(
        sessionId: String,
        directory: String? = null,
        messageId: String? = null,
    ): List<OpenCodeFileChange>

    suspend fun sessionTodo(
        sessionId: String,
        directory: String? = null,
    ): List<OpenCodeTodo>

    suspend fun promptAsync(
        sessionId: String,
        request: PromptRequest,
    )

    suspend fun summarizeSession(
        sessionId: String,
        providerId: String,
        modelId: String,
    ): Boolean

    suspend fun abortSession(sessionId: String): Boolean

    suspend fun renameSession(
        sessionId: String,
        title: String,
        directory: String? = null,
    ): OpenCodeSession

    suspend fun deleteSession(
        sessionId: String,
        directory: String? = null,
    ): Boolean

    suspend fun archiveSession(
        sessionId: String,
        directory: String? = null,
    ): OpenCodeSession

    suspend fun mcpServers(): List<McpServer>

    suspend fun addMcpServer(body: JsonObject): McpServer

    suspend fun connectMcpServer(name: String): Boolean

    suspend fun disconnectMcpServer(name: String): Boolean

    suspend fun removeMcpAuth(name: String): McpAuthRemoval

    suspend fun mcpAuth(name: String): McpAuthStart

    suspend fun mcpAuthCallback(
        name: String,
        code: String,
    ): McpAuthStatus

    suspend fun config(): JsonElement

    suspend fun updateConfig(patch: JsonObject): JsonElement

    suspend fun configProviders(): List<ConfiguredProvider>

    suspend fun commands(): List<OpenCodeCommand>

    suspend fun skills(): List<OpenCodeSkill>

    suspend fun executeCommand(
        sessionId: String,
        command: String,
        arguments: String = "",
        agent: String? = null,
        model: String? = null,
        variant: String? = null,
    )

    suspend fun respondPermission(
        sessionId: String,
        permissionId: String,
        response: String,
        remember: Boolean = false,
    ): Boolean

    suspend fun answerQuestion(
        requestId: String,
        answers: List<List<String>>,
        directory: String? = null,
    ): Boolean

    suspend fun rejectQuestion(
        requestId: String,
        directory: String? = null,
    ): Boolean

    suspend fun pendingQuestions(directory: String? = null): List<QuestionRequest>

    fun events(): Flow<OpenCodeEvent>
}
