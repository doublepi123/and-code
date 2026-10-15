package com.yugahashimoto.andcode.live

import com.yugahashimoto.andcode.core.api.OpenCodeApiException
import com.yugahashimoto.andcode.core.api.OpenCodeEvent
import com.yugahashimoto.andcode.core.api.OpenCodeEvent.QuestionAsked
import com.yugahashimoto.andcode.core.api.OpenCodePart
import com.yugahashimoto.andcode.core.api.OpenCodeProtocol
import com.yugahashimoto.andcode.core.api.PromptRequest
import com.yugahashimoto.andcode.core.api.sessionIdOrNull
import com.yugahashimoto.andcode.data.connection.ConnectionProfile
import com.yugahashimoto.andcode.runtime.PermissionResponse
import com.yugahashimoto.andcode.runtime.remote.RemoteOpenCodeBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import com.yugahashimoto.andcode.core.api.OpenCodeEvent.MessagePartDelta as PartDelta
import com.yugahashimoto.andcode.core.api.OpenCodeEvent.MessagePartUpdated as PartUpdated
import com.yugahashimoto.andcode.core.api.OpenCodeEvent.PermissionAsked as PermAsked
import com.yugahashimoto.andcode.core.api.OpenCodeEvent.PermissionReplied as PermReplied
import com.yugahashimoto.andcode.core.api.OpenCodeEvent.SessionError as SessErr
import com.yugahashimoto.andcode.core.api.OpenCodeEvent.SessionIdle as SessIdle
import com.yugahashimoto.andcode.core.api.OpenCodeEvent.SessionStatusChanged as SessStatus

/**
 * End-to-end test of the V2 client through the public [RemoteOpenCodeBackend] surface, against a
 * REAL OpenCode v2 server. Skipped unless `OPENCODE_LIVE_URL` is set, so CI never runs it.
 *
 * Environment:
 * - OPENCODE_LIVE_URL       server base URL, e.g. http://127.0.0.1:17895 (required)
 * - OPENCODE_LIVE_DIR       workspace directory as the SERVER sees it (required)
 * - OPENCODE_LIVE_PASSWORD  server password (username is fixed to `opencode` by the V2 server)
 * - OPENCODE_LIVE_PROVIDER  default `opencode`
 * - OPENCODE_LIVE_MODEL     default `cat-jam-free`
 * - OPENCODE_LIVE_VERSION   expected server version, default `2.0.28`
 *
 * The server is expected to have `"permission": {"shell": "ask"}` in its config. The turn scenario
 * approves shell requests automatically; the permission scenario answers them explicitly.
 */
class OpenCodeV2LiveTest {
    private var url = ""
    private var password: String? = null
    private var dir = ""
    private var providerId = "opencode"
    private var modelId = "cat-jam-free"
    private var expectedVersion = "2.0.28"

    @Before
    fun readLiveEnvironment() {
        url = env("OPENCODE_LIVE_URL").orEmpty()
        Assume.assumeTrue("OPENCODE_LIVE_URL is not set; live OpenCode V2 test skipped", url.isNotBlank())
        dir = env("OPENCODE_LIVE_DIR").orEmpty()
        Assume.assumeTrue("OPENCODE_LIVE_DIR is not set; live OpenCode V2 test skipped", dir.isNotBlank())
        password = env("OPENCODE_LIVE_PASSWORD")
        providerId = env("OPENCODE_LIVE_PROVIDER") ?: providerId
        modelId = env("OPENCODE_LIVE_MODEL") ?: modelId
        expectedVersion = env("OPENCODE_LIVE_VERSION") ?: expectedVersion
    }

    // 1. health and authentication -------------------------------------------------------------

    @Test
    fun `health reports version 2 and wrong password is a 401`() =
        runBlocking {
            withTimeout(WITHIN_MS) {
                val good = RemoteOpenCodeBackend(profile(password))
                assertEquals(null, good.protocol)
                val health = good.health()
                assertTrue(health.healthy)
                assertEquals(expectedVersion, health.version)
                assertEquals(OpenCodeProtocol.V2, good.protocol)

                val bad = RemoteOpenCodeBackend(profile("definitely-wrong-password"))
                val error =
                    try {
                        bad.health()
                        null
                    } catch (expected: OpenCodeApiException) {
                        expected
                    }
                assertNotNull("wrong password must not succeed", error)
                assertEquals(401, error!!.statusCode)
                assertEquals(OpenCodeProtocol.V2, bad.protocol)
            }
        }

    // 2. session lifecycle -----------------------------------------------------------------------

    @Test
    fun `session create list rename archive and delete`() =
        runBlocking {
            withTimeout(WITHIN_MS) {
                val backend = RemoteOpenCodeBackend(profile(password))
                backend.health()
                val title = "live-a-${System.currentTimeMillis()}"
                val a = backend.createSession(title, dir)
                assertTrue("created session must be listed", backend.listSessions(dir).any { it.id == a.id })

                val renamed = backend.renameSession(a.id, "$title-renamed")
                assertEquals("$title-renamed", renamed.title)
                assertEquals("$title-renamed", backend.session(a.id).title)

                val b = backend.createSession("live-b-$title", dir)
                assertTrue(backend.deleteSession(b.id))
                assertFalse("deleted session must not be listed", backend.listSessions(dir).any { it.id == b.id })
                val gone =
                    try {
                        backend.session(b.id)
                        null
                    } catch (expected: OpenCodeApiException) {
                        expected
                    }
                assertEquals("deleted session must be 404", 404, gone?.statusCode)

                val archived = backend.archiveSession(a.id)
                assertNotNull("archive must set time.archived on the returned session", archived.time.archived)
                assertFalse(
                    "archived session must disappear from listSessions",
                    backend.listSessions(dir).any { it.id == a.id },
                )
            }
        }

    // 3. read-only catalog and workspace --------------------------------------------------------

    @Test
    fun `catalog providers agents commands skills config files and vcs`() =
        runBlocking {
            withTimeout(WITHIN_MS) {
                val backend = RemoteOpenCodeBackend(profile(password))
                backend.health()

                val catalog = backend.listProviders()
                val provider = catalog.all.firstOrNull { it.id == providerId }
                assertNotNull("provider $providerId missing from ${catalog.all.map { it.id }}", provider)
                assertTrue("model $modelId missing", provider!!.models.containsKey(modelId))
                assertNotNull("no default model for $providerId", catalog.default[providerId])

                assertTrue("agent build missing", backend.listAgents().any { it.name == "build" })
                assertTrue("commands empty", backend.commands().isNotEmpty())
                assertTrue("skills empty", backend.skills().isNotEmpty())

                val config = backend.config()
                assertTrue("config must be a merged object: $config", config is JsonObject)
                // V2 keeps the document's own keys under `info`; the merged view must carry the `shell: ask` rule.
                assertTrue("config must carry the shell ask rule from opencode.json: $config", config.toString().contains("\"shell\""))

                val files = backend.listFiles(dir, ".")
                assertTrue("README.md missing from $files", files.any { it.name == "README.md" && it.type == "file" })
                assertEquals("hello\n", backend.readFile(dir, "README.md").content)

                assertNotNull("vcs branch missing", backend.vcsInfo(dir).branch)
            }
        }

    // 4. real turn: streaming, tool, reload -----------------------------------------------------

    @Test
    fun `real turn streams text and shell tool then reloads without duplicate parts`() =
        runBlocking {
            modelRetry("turn") { attempt ->
                val prompt = if (attempt == 1) TURN_PROMPT else TURN_PROMPT_REPHRASED
                live(autoApprove = true) { live ->
                    val sid = live.backend.createSession("live-turn", dir).id
                    val mark = live.mark()
                    live.backend.sendMessage(sid, promptFor(prompt))
                    live.awaitOrFail("SessionIdle", TURN_MS, mark) { it is SessIdle && it.sessionId == sid }
                    live.failOnProviderError(sid, mark)

                    val streamed = live.eventsFor(sid, mark)
                    assertTrue("no busy status", streamed.any { it is SessStatus && it.status == "busy" })
                    assertTrue("no text streamed", streamed.any { it.carriesText() })
                    val tools = streamed.filterIsInstance<PartUpdated>().map { it.part }.filter { it.type == "tool" }
                    if (tools.none { it.tool == "shell" }) throw ModelFlake("model did not call the shell tool")
                    assertTrue("shell tool never completed", tools.any { it.tool == "shell" && statusOf(it) == "completed" })

                    val messages = live.backend.listMessages(sid)
                    assertEquals(
                        "prompt must be stored exactly once",
                        1,
                        messages.count { it.info.role == "user" && it.text == prompt },
                    )
                    val reloadedParts = messages.flatMap { it.parts }
                    val reloadedIds = reloadedParts.mapNotNull { it.id }
                    assertEquals("duplicate part ids after reload", reloadedIds.size, reloadedIds.toSet().size)

                    val streamedIds =
                        streamed.mapNotNull { event ->
                            when (event) {
                                is PartUpdated -> event.part.id?.takeIf { event.part.type == "text" || event.part.type == "tool" }
                                is PartDelta -> event.partId.takeIf { event.field == "text" }
                                else -> null
                            }
                        }.toSet()
                    val missing = streamedIds - reloadedIds.toSet()
                    assertTrue("streamed part ids not found after reload: $missing", missing.isEmpty())

                    val reloadedTool = reloadedParts.firstOrNull { it.type == "tool" && it.tool == "shell" }
                    assertNotNull("shell tool part missing after reload", reloadedTool)
                    assertEquals("completed", statusOf(reloadedTool!!))

                    val finalText = assistantText(live.backend, sid)
                    if (!finalText.contains("DONE", ignoreCase = true)) throw ModelFlake("reply lacked DONE: '$finalText'")
                }
            }
        }

    // 5. permission round trip ---------------------------------------------------------------

    @Test
    fun `shell permission asks and a once reply resumes the turn`() =
        runBlocking {
            modelRetry("permission") { attempt ->
                val prompt = if (attempt == 1) PERMISSION_PROMPT else PERMISSION_PROMPT_REPHRASED
                live(autoApprove = false) { live ->
                    val sid = live.backend.createSession("live-permission", dir).id
                    val mark = live.mark()
                    live.backend.sendMessage(sid, promptFor(prompt))
                    val asked =
                        live.awaitOrNull(TURN_MS, mark) { it is PermAsked && it.request.sessionId == sid } as? PermAsked
                    if (asked == null) {
                        if (live.eventsFor(sid, mark).none { it is PartUpdated && it.part.type == "tool" }) {
                            throw ModelFlake("model did not call the shell tool, so no permission was asked")
                        }
                        fail("a tool ran but no permission was asked")
                    }
                    val request = asked!!.request
                    assertEquals("shell", request.permission)
                    assertTrue(live.backend.respondToPermission(sid, request.id, PermissionResponse.ONCE, remember = false))

                    live.awaitOrFail("PermissionReplied", WITHIN_SHORT_MS, mark) {
                        it is PermReplied && it.requestId == request.id && it.sessionId == sid
                    }
                    live.awaitOrFail("SessionIdle", TURN_MS, mark) { it is SessIdle && it.sessionId == sid }
                    live.failOnProviderError(sid, mark)
                    assertTrue(
                        "shell tool did not complete after approval",
                        live.eventsFor(sid, mark).filterIsInstance<PartUpdated>()
                            .any { it.part.tool == "shell" && statusOf(it.part) == "completed" },
                    )
                }
            }
        }

    // 6. question form ------------------------------------------------------------------------

    @Test
    fun `question form is listed, answered and resolves the turn`() =
        runBlocking {
            modelRetry("question") { attempt ->
                val prompt = if (attempt == 1) QUESTION_PROMPT else QUESTION_PROMPT_REPHRASED
                live { live ->
                    val sid = live.backend.createSession("live-question", dir).id
                    val mark = live.mark()
                    live.backend.sendMessage(sid, promptFor(prompt))
                    val asked = live.awaitOrNull(TURN_MS, mark) { it is QuestionAsked && it.request.sessionId == sid } as? QuestionAsked
                    if (asked == null) throw ModelFlake("model did not use the question tool")
                    val request = asked.request
                    val labels = request.questions.flatMap { question -> question.options.map { it.label } }
                    assertTrue("options Red and Blue missing, got $labels", labels.containsAll(listOf("Red", "Blue")))

                    val pending = live.backend.pendingQuestions(dir)
                    assertTrue("pending question ${request.id} missing: ${pending.map { it.id }}", pending.any { it.id == request.id })

                    assertTrue(live.backend.answerQuestion(request.id, listOf(listOf("Blue")), dir))
                    live.awaitOrFail("SessionIdle", TURN_MS, mark) { it is SessIdle && it.sessionId == sid }
                    live.failOnProviderError(sid, mark)

                    assertFalse(
                        "answered question must no longer be pending",
                        live.backend.pendingQuestions(dir).any { it.id == request.id },
                    )
                    val text = assistantText(live.backend, sid)
                    if (!text.contains("Blue", ignoreCase = true)) throw ModelFlake("final text does not mention Blue: '$text'")
                }
            }
        }

    // 7. abort ----------------------------------------------------------------------------------

    @Test
    fun `abort stops a long turn and leaves no non-abort error`() =
        runBlocking {
            modelRetry("abort") {
                // Auto-approve: the model may still reach for a tool, and an unanswered permission would block the turn.
                live(autoApprove = true) { live ->
                    val sid = live.backend.createSession("live-abort", dir).id
                    val mark = live.mark()
                    live.backend.sendMessage(sid, promptFor(ABORT_PROMPT))
                    // Abort as soon as the turn is busy: the free model can finish a short reply in a few seconds.
                    live.awaitOrFail(
                        "SessionStatusChanged busy",
                        WITHIN_MS,
                        mark,
                    ) { it is SessStatus && it.sessionId == sid && it.status == "busy" }
                    if (live.eventsFor(sid, mark).any {
                            it is SessIdle
                        }
                    ) {
                        throw ModelFlake("turn finished before abort was sent: ${live.errorDetail(sid, mark)}")
                    }

                    val abortMark = live.mark()
                    assertTrue(live.backend.abortSession(sid))
                    live.awaitOrFail("SessionIdle after abort", WITHIN_SHORT_MS, abortMark) { it is SessIdle && it.sessionId == sid }

                    val failures = live.eventsFor(sid, mark).filterIsInstance<SessErr>().filterNot { it.isAbort }
                    if (failures.isNotEmpty()) throw ModelFlake("non-abort SessionError around abort: $failures")
                }
            }
        }

    // 8. summarize ------------------------------------------------------------------------------

    @Test
    fun `summarize compacts a session that has a turn`() =
        runBlocking {
            modelRetry("summarize") {
                live { live ->
                    val sid = live.backend.createSession("live-summarize", dir).id
                    val mark = live.mark()
                    live.backend.sendMessage(sid, promptFor("Reply with the single word OK."))
                    live.awaitOrFail("SessionIdle", TURN_MS, mark) { it is SessIdle && it.sessionId == sid }
                    live.failOnProviderError(sid, mark)
                    assertTrue(live.backend.summarizeSession(sid, providerId, modelId))
                }
            }
        }

    // ------------------------------------------------------------------------------------------

    private fun env(name: String): String? = System.getenv(name)?.trim()?.takeIf { it.isNotEmpty() }

    private fun profile(pw: String?) = ConnectionProfile(name = "live", baseUrl = url, password = pw?.takeIf { it.isNotBlank() })

    private fun promptFor(text: String) = PromptRequest(text = text, providerId = providerId, modelId = modelId, agent = "build")

    private suspend fun assistantText(
        backend: RemoteOpenCodeBackend,
        sid: String,
    ): String =
        backend.listMessages(sid)
            .filter { it.info.role == "assistant" }
            .joinToString("\n") { it.text }

    private fun statusOf(part: OpenCodePart): String? = ((part.state?.get("status")) as? JsonPrimitive)?.contentOrNull

    private fun OpenCodeEvent.carriesText(): Boolean =
        when (this) {
            is PartUpdated -> part.type == "text"
            is PartDelta -> field == "text"
            else -> false
        }

    /** Runs [block] with a live event recorder; the recorder is cancelled when the block ends. */
    private suspend fun <T> live(
        autoApprove: Boolean = false,
        block: suspend (Live) -> T,
    ): T {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val live = Live(RemoteOpenCodeBackend(profile(password)), scope, autoApprove)
            live.start()
            // The first SSE frame proves the stream is open; the channel buffers it until we read it.
            live.awaitOrFail("server.connected", WITHIN_SHORT_MS, 0) { it == OpenCodeEvent.ServerConnected }
            return block(live)
        } finally {
            scope.cancel()
        }
    }

    /**
     * Retries a model-driven scenario once. Only [ModelFlake] (the model or provider misbehaved)
     * triggers the retry; client bugs fail on the first attempt.
     */
    private suspend fun <T> modelRetry(
        name: String,
        block: suspend (attempt: Int) -> T,
    ): T =
        try {
            block(1)
        } catch (flake: ModelFlake) {
            println("LIVE-FLAKE[$name] attempt 1: ${flake.message}; retrying once")
            block(2)
        }

    private class ModelFlake(message: String) : AssertionError(message)

    private inner class Live(
        val backend: RemoteOpenCodeBackend,
        private val scope: CoroutineScope,
        private val autoApprove: Boolean,
    ) {
        val events = CopyOnWriteArrayList<OpenCodeEvent>()

        fun start() {
            scope.launch {
                backend.events().collect { event ->
                    events += event
                    if (autoApprove && event is PermAsked) {
                        val request = event.request
                        scope.launch {
                            backend.respondToPermission(request.sessionId, request.id, PermissionResponse.ONCE, remember = false)
                        }
                    }
                }
            }
        }

        fun mark(): Int = events.size

        fun eventsFor(
            sid: String,
            from: Int,
        ): List<OpenCodeEvent> = events.drop(from).filter { it.sessionIdOrNull() == sid }

        suspend fun awaitOrNull(
            timeoutMs: Long,
            from: Int,
            predicate: (OpenCodeEvent) -> Boolean,
        ): OpenCodeEvent? =
            withTimeoutOrNull(timeoutMs) {
                var found: OpenCodeEvent? = null
                while (found == null) {
                    found = events.drop(from).firstOrNull(predicate)
                    if (found == null) delay(POLL_MS)
                }
                found
            }

        suspend fun awaitOrFail(
            what: String,
            timeoutMs: Long,
            from: Int,
            predicate: (OpenCodeEvent) -> Boolean,
        ): OpenCodeEvent =
            awaitOrNull(timeoutMs, from, predicate)
                ?: throw AssertionError(
                    "timed out after ${timeoutMs}ms waiting for $what; last events: ${events.takeLast(15).map { it::class.simpleName }}",
                )

        /** Name and message of the session's errors, for flake diagnostics. */
        fun errorDetail(
            sid: String,
            from: Int,
        ): String =
            eventsFor(sid, from).filterIsInstance<SessErr>().joinToString { "${it.name}: ${it.message}" }.ifEmpty { "no session error" }

        /** A non-abort session error means the provider or model failed; the chat must still have settled. */
        fun failOnProviderError(
            sid: String,
            from: Int,
        ) {
            val error = eventsFor(sid, from).filterIsInstance<SessErr>().firstOrNull { !it.isAbort }
            if (error != null) throw ModelFlake("session error ${error.name}: ${error.message}")
        }
    }

    private companion object {
        const val POLL_MS = 100L
        const val WITHIN_SHORT_MS = 30_000L
        const val WITHIN_MS = 60_000L
        const val TURN_MS = 120_000L

        const val TURN_PROMPT = "Run `ls` using the shell tool, then reply with the single word DONE."
        const val TURN_PROMPT_REPHRASED = "Call the shell tool with the command `ls`. After the tool returns, reply with only the word DONE."
        const val PERMISSION_PROMPT = "Run `pwd` using the shell tool, then reply with the single word DONE."
        const val PERMISSION_PROMPT_REPHRASED = "Use the shell tool to run `pwd`. Then reply with only the word DONE."
        const val QUESTION_PROMPT = "Use the question tool to ask me whether I prefer Red or Blue (options Red, Blue). Then reply with my answer."
        const val QUESTION_PROMPT_REPHRASED = "Ask me with the question tool: Red or Blue? Options: Red, Blue. Then reply with my answer."
        const val ABORT_PROMPT = "Count slowly from 1 to 200, one number per line"
    }
}
