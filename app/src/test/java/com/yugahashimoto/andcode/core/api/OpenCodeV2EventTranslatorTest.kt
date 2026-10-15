package com.yugahashimoto.andcode.core.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Replays the fixtures captured from a live OpenCode V2 server (v2.0.28), one `data:` line per
 * event, so the mapping is checked against the real wire shapes rather than hand-written ones.
 */
class OpenCodeV2EventTranslatorTest {
    private val formRegistry = OpenCodeV2FormRegistry()

    /** Fixed clock: the replays never cross the liveness interval, so the throttle is deterministic. */
    private var now = 0L
    private val translator =
        OpenCodeV2EventTranslator(Json { ignoreUnknownKeys = true }, formRegistry, clock = { now })

    private val session = "ses_ec0ed8d01ffeMCMAnsvqKvcZlP"
    private val firstAssistant = "msg_13f12d484001x1GrZf4FsEpZFE"
    private val secondAssistant = "msg_13f12d8e7001yF1vGKVpI8umGb"
    private val shellCall = "call-e449b4e6-9d1e-499b-9677-454de839b8ad"

    /** The execution-failed fixture was captured in its own session (turn 7), not the main one. */
    private val failureSession = "ses_ec0e9d9daffeZbW7wReVzRsWUp"
    private val failedAssistant = "msg_13f162a5c001TPmshXTmXrp2M3"

    @Test
    fun `text and reasoning turn maps to V1 part snapshots and deltas`() {
        val events = replay("text_tools.jsonl")

        assertEquals(OpenCodeEvent.ServerConnected, events.first())
        assertEquals(OpenCodeEvent.SessionStatusChanged(session, "busy"), events[1])
        assertEquals(OpenCodeEvent.SessionIdle(session), events.last())

        val steps = events.filterIsInstance<OpenCodeEvent.MessageUpdated>()
        assertEquals(listOf(firstAssistant, secondAssistant), steps.map { it.info.id }.distinct())
        assertTrue(steps.all { it.info.role == "assistant" && it.info.sessionId == session })
        assertEquals(OpenCodeModelReference("opencode", "cat-jam-free"), steps.first().info.model)
        assertEquals("build", steps.first().info.agent)
        // Tokens arrive on step.ended, the first snapshot that carries them.
        assertEquals(2408L, steps.first { it.info.tokens != null }.info.tokens?.input)

        // The blank started snapshot is followed by the full ended text, which replaces the deltas.
        val textSnapshots =
            events.filterIsInstance<OpenCodeEvent.MessagePartUpdated>()
                .filter { it.part.id == "$secondAssistant-text-0" }
        assertEquals(listOf("", "DONE"), textSnapshots.map { it.part.text })
        assertEquals(
            OpenCodeEvent.MessagePartDelta(session, secondAssistant, "$secondAssistant-text-0", "text", "DONE"),
            events.filterIsInstance<OpenCodeEvent.MessagePartDelta>().last { it.field == "text" },
        )

        val reasoningDeltas = events.filterIsInstance<OpenCodeEvent.MessagePartDelta>().filter { it.field == "reasoning" }
        assertEquals(2, reasoningDeltas.size)
        assertEquals("$firstAssistant-reasoning-0", reasoningDeltas.first().partId)

        // The reasoning part's ended snapshot carries the full text under the reasoning type.
        val reasoningEnded =
            events.filterIsInstance<OpenCodeEvent.MessagePartUpdated>()
                .first { it.part.id == "$firstAssistant-reasoning-0" && it.part.text?.isNotEmpty() == true }
        assertEquals("reasoning", reasoningEnded.part.type)
        assertTrue(reasoningEnded.part.text!!.startsWith("The user wants me to run `ls`"))
    }

    @Test
    fun `shell tool turn keeps name and input across pending running and completed`() {
        val toolParts =
            replay("text_tools.jsonl")
                .filterIsInstance<OpenCodeEvent.MessagePartUpdated>()
                .filter { it.part.type == "tool" }
                .map { it.part }

        assertEquals(
            listOf("pending", "pending", "running", "running", "completed"),
            toolParts.map { it.state?.get("status")?.jsonPrimitive?.content },
        )
        // The tool part id is the call id, the same id the V2 message mapper uses.
        assertTrue(toolParts.all { it.id == shellCall && it.callID == shellCall && it.tool == "shell" })
        assertTrue(toolParts.all { it.messageId == firstAssistant && it.sessionId == session })

        val input = toolParts[1].state?.get("input") as JsonObject
        assertEquals("ls", input["command"]?.jsonPrimitive?.content)
        // The later running update still carries the input that arrived before it.
        assertEquals(input, toolParts[2].state?.get("input"))

        val completed = toolParts.last().state!!
        assertEquals("README.md\n", completed["output"]?.jsonPrimitive?.content)
        assertNotNull(completed["metadata"])
    }

    @Test
    fun `permission ask and reply map to V1 permission events`() {
        val events = replay("permission.jsonl")

        val asked = events.filterIsInstance<OpenCodeEvent.PermissionAsked>().single().request
        assertEquals("per_13f133279001Ah85Mwz5YJSy1r", asked.id)
        assertEquals(session, asked.sessionId)
        assertEquals("shell", asked.permission)
        assertEquals(listOf("pwd"), asked.patterns)
        assertEquals(setOf("save", "source"), asked.metadata.keys)

        assertEquals(
            OpenCodeEvent.PermissionReplied(session, "per_13f133279001Ah85Mwz5YJSy1r"),
            events.filterIsInstance<OpenCodeEvent.PermissionReplied>().single(),
        )
    }

    @Test
    fun `form created becomes a question with the field options and directory`() {
        val events = replay("form.jsonl")

        val question = events.filterIsInstance<OpenCodeEvent.QuestionAsked>().single().request
        assertEquals("frm_13f137bc90017JjU2piWI5BVi6", question.id)
        assertEquals(session, question.sessionId)
        assertEquals("/tmp/opencode/v2contract/srv/work", question.directory)
        assertEquals(1, question.questions.size)

        val prompt = question.questions.single()
        assertEquals("Which color do you prefer?", prompt.question)
        assertEquals("Color preference", prompt.header)
        assertEquals(listOf("Red", "Blue"), prompt.options.map { it.label })
        assertTrue(prompt.custom)
        assertTrue(!prompt.multiple)

        // Remembered so the answer can be posted without another lookup.
        assertEquals(session, formRegistry.get("frm_13f137bc90017JjU2piWI5BVi6")?.sessionId)
    }

    @Test
    fun `form replied and cancelled forget the form and emit nothing`() {
        formRegistry.remember(V2Form(id = "frm_1", sessionId = session, fields = emptyList()))

        val replied =
            """{"id":"evt_1","type":"form.replied","location":{"directory":"/w"},"data":{"id":"frm_1","sessionID":"$session","answer":{"q0":"Red"}}}"""
        assertEquals(emptyList<OpenCodeEvent>(), translator.translate(replied))
        assertNull(formRegistry.get("frm_1"))

        val cancelled = """{"id":"evt_2","type":"form.cancelled","data":{"id":"frm_2","sessionID":"$session"}}"""
        assertEquals(emptyList<OpenCodeEvent>(), translator.translate(cancelled))
    }

    @Test
    fun `failed execution emits the error then idle`() {
        val events = replay("execution_failed.jsonl")

        val error = events.filterIsInstance<OpenCodeEvent.SessionError>().single()
        assertEquals(failureSession, error.sessionId)
        assertEquals("provider.invalid-request", error.name)
        assertTrue(error.message!!.startsWith("Upstream request failed: [invalid_parameter] Image too small"))
        assertTrue(!error.isAbort)

        val failureIndex = events.indexOf(error)
        assertEquals(OpenCodeEvent.SessionIdle(failureSession), events[failureIndex + 1])
        assertEquals(OpenCodeEvent.SessionIdle(failureSession), events.last())

        // The step failure also reaches the message as an error, so the chat can attach it to the reply.
        val stepFailure = events.filterIsInstance<OpenCodeEvent.MessageUpdated>().last().info
        assertEquals(failedAssistant, stepFailure.id)
        assertEquals("provider.invalid-request", stepFailure.error?.name)
    }

    /**
     * Item (a): a stop from any device is reported the way V1 reports one: a MessageAbortedError
     * session error (which mutes the completion notification) followed by idle. Also item (c): the
     * compaction in this capture is liveness, not transcript text.
     */
    @Test
    fun `interrupted run is an abort error then idle and compaction is liveness only`() {
        now = 0L
        val events = replay("interrupted.jsonl")

        // Real order: execution started (busy), compaction started (busy), first delta (liveness),
        // second delta (inside the 5s throttle, dropped), compaction failed (nothing),
        // execution interrupted (abort error then idle).
        assertEquals(
            listOf(
                OpenCodeEvent.ServerConnected,
                OpenCodeEvent.SessionStatusChanged(session, "busy"),
                OpenCodeEvent.SessionStatusChanged(session, "busy"),
                livenessDelta(),
                OpenCodeEvent.SessionError(session, message = null, name = "MessageAbortedError"),
                OpenCodeEvent.SessionIdle(session),
            ),
            events,
        )
        assertTrue((events[4] as OpenCodeEvent.SessionError).isAbort)
    }

    @Test
    fun `interrupted without an error still reports an abort so it is not a completion`() {
        val events =
            translator.translate(
                """{"id":"e1","type":"session.execution.interrupted","data":{"sessionID":"$session","reason":"user"}}""",
            )
        assertEquals(
            listOf(
                OpenCodeEvent.SessionError(session, message = null, name = "MessageAbortedError"),
                OpenCodeEvent.SessionIdle(session),
            ),
            events,
        )
    }

    @Test
    fun `aborted error type is mapped to the V1 abort name on step and execution failures`() {
        val step =
            translator.translate(
                """{"id":"e1","type":"session.step.failed","data":{"sessionID":"$session","assistantMessageID":"$firstAssistant","error":{"type":"aborted","message":"Stopped"}}}""",
            ).single() as OpenCodeEvent.MessageUpdated
        assertEquals("MessageAbortedError", step.info.error?.name)
        assertTrue(step.info.error!!.isAbort)

        val execution =
            translator.translate(
                """{"id":"e2","type":"session.execution.failed","data":{"sessionID":"$session","error":{"type":"aborted","message":"Stopped"}}}""",
            )
        assertEquals(
            listOf(
                OpenCodeEvent.SessionError(session, "Stopped", "MessageAbortedError"),
                OpenCodeEvent.SessionIdle(session),
            ),
            execution,
        )
    }

    /** Item (b): a fork's parentID is its source, so the translator must not announce it as a parent. */
    @Test
    fun `session forked and session created emit nothing so the REST parent lookup decides`() {
        assertEquals(emptyList<OpenCodeEvent>(), replay("session_created.jsonl"))
    }

    @Test
    fun `session renamed and moved are dropped like other chatter`() {
        assertEquals(
            emptyList<OpenCodeEvent>(),
            translator.translate("""{"id":"evt_1","type":"session.renamed","data":{"sessionID":"$session","title":"x"}}"""),
        )
        assertEquals(
            emptyList<OpenCodeEvent>(),
            translator.translate(
                """{"id":"evt_2","type":"session.moved","data":{"sessionID":"$session","location":{"directory":"/w2"}}}""",
            ),
        )
    }

    @Test
    fun `compaction start is busy and its deltas are throttled liveness`() {
        now = 0L
        assertEquals(
            OpenCodeEvent.SessionStatusChanged(session, "busy"),
            translator.translate(
                """{"id":"e1","type":"session.compaction.started","data":{"sessionID":"$session","reason":"manual"}}""",
            ).single(),
        )

        val delta = """{"id":"e2","type":"session.compaction.delta","data":{"sessionID":"$session","text":"## Objective"}}"""
        assertEquals(listOf(livenessDelta()), translator.translate(delta))
        now = 4_999L
        assertEquals(emptyList<OpenCodeEvent>(), translator.translate(delta))
        now = 5_000L
        assertEquals(listOf(livenessDelta()), translator.translate(delta))
    }

    @Test
    fun `compaction failure emits nothing because execution settles the run`() {
        assertEquals(
            emptyList<OpenCodeEvent>(),
            translator.translate(
                """{"id":"e1","type":"session.compaction.failed","data":{"sessionID":"$session","reason":"manual","error":{"type":"aborted","message":"Compaction cancelled"}}}""",
            ),
        )
    }

    @Test
    fun `long generation without visible text keeps the session alive via throttled liveness`() {
        now = 0L
        val streamed = """{"id":"e1","type":"session.step.streamed","data":{"sessionID":"$session","assistantMessageID":"$firstAssistant"}}"""
        val usage = """{"id":"e2","type":"session.usage.updated","data":{"sessionID":"$session","cost":0,"tokens":{}}}"""

        assertEquals(listOf(livenessDelta()), translator.translate(streamed))
        now = 1_000L
        assertEquals(emptyList<OpenCodeEvent>(), translator.translate(streamed))
        now = 2_000L
        assertEquals(emptyList<OpenCodeEvent>(), translator.translate(usage))
        now = 5_000L
        assertEquals(listOf(livenessDelta()), translator.translate(usage))
    }

    @Test
    fun `replayed turn never emits a delta on a field the chat would render`() {
        now = 0L
        val events = replay("text_tools.jsonl")
        // Every delta is either visible text/reasoning or the liveness marker the chat ignores.
        val deltas = events.filterIsInstance<OpenCodeEvent.MessagePartDelta>()
        assertTrue(deltas.all { it.field == "text" || it.field == "reasoning" || it.field == "liveness" })
        assertTrue(deltas.filter { it.field == "liveness" }.all { it.delta.isEmpty() && it.sessionId == session })
    }

    @Test
    fun `seeded tool keeps its name and input across a reconnect`() {
        val input = Json.parseToJsonElement("""{"command":"ls"}""")
        translator.seedTool(shellCall, "shell", input)

        val progress =
            translator.translate(
                """{"id":"e1","type":"session.tool.progress","data":{"sessionID":"$session","assistantMessageID":"$firstAssistant","id":"$shellCall","metadata":{"shellID":"sh_1"}}}""",
            ).single() as OpenCodeEvent.MessagePartUpdated

        assertEquals("shell", progress.part.tool)
        assertEquals(input, progress.part.state?.get("input"))
        assertEquals("running", progress.part.state?.get("status")?.jsonPrimitive?.content)
    }

    @Test
    fun `tool failure keeps the call name and reports the message`() {
        translator.translate(
            """{"id":"e1","type":"session.tool.input.started","data":{"sessionID":"$session","assistantMessageID":"$firstAssistant","id":"$shellCall","name":"shell"}}""",
        )
        val failed =
            translator.translate(
                """{"id":"e2","type":"session.tool.failed","data":{"sessionID":"$session","assistantMessageID":"$firstAssistant","id":"$shellCall","error":{"type":"tool.execution","message":"user cancelled"},"executed":false}}""",
            ).single() as OpenCodeEvent.MessagePartUpdated

        assertEquals("shell", failed.part.tool)
        assertEquals("error", failed.part.state?.get("status")?.jsonPrimitive?.content)
        assertEquals("user cancelled", failed.part.state?.get("error")?.jsonPrimitive?.content)
    }

    @Test
    fun `todowrite input passes through unchanged`() {
        val input = """{"todos":[{"content":"Ship","status":"pending","priority":"high"}]}"""
        translator.translate(
            """{"id":"e1","type":"session.tool.input.started","data":{"sessionID":"$session","assistantMessageID":"$firstAssistant","id":"call-todo","name":"todowrite"}}""",
        )
        val called =
            translator.translate(
                """{"id":"e2","type":"session.tool.called","data":{"sessionID":"$session","assistantMessageID":"$firstAssistant","id":"call-todo","input":$input,"executed":false}}""",
            ).single() as OpenCodeEvent.MessagePartUpdated

        assertEquals("todowrite", called.part.tool)
        assertEquals(Json.parseToJsonElement(input), called.part.state?.get("input"))
    }

    @Test
    fun `malformed json yields nothing`() {
        assertEquals(emptyList<OpenCodeEvent>(), translator.translate("not json"))
        assertEquals(emptyList<OpenCodeEvent>(), translator.translate("{"))
        assertEquals(emptyList<OpenCodeEvent>(), translator.translate("""{"data":{}}"""))
        assertEquals(emptyList<OpenCodeEvent>(), translator.translate("[1,2]"))
    }

    @Test
    fun `known type with a broken body becomes unknown`() {
        val raw = """{"type":"session.text.delta","data":{"sessionID":"s"}}"""
        assertEquals(listOf(OpenCodeEvent.Unknown("session.text.delta", raw)), translator.translate(raw))
    }

    @Test
    fun `unrecognised type becomes unknown`() {
        val raw = """{"type":"future.thing","data":{}}"""
        assertEquals(listOf(OpenCodeEvent.Unknown("future.thing", raw)), translator.translate(raw))
    }

    @Test
    fun `part ids match the V2 message mapper scheme`() {
        val delta =
            translator.translate(
                """{"type":"session.text.delta","data":{"sessionID":"$session","assistantMessageID":"$firstAssistant","ordinal":2,"delta":"x"}}""",
            ).single() as OpenCodeEvent.MessagePartDelta

        assertEquals(OpenCodeV2Ids.textPartId(firstAssistant, 2), delta.partId)
    }

    /** The liveness-only delta the translator emits for [session]; see livenessThrottled. */
    private fun livenessDelta() =
        OpenCodeEvent.MessagePartDelta(sessionId = session, messageId = "", partId = "", field = "liveness", delta = "")

    private fun replay(resource: String): List<OpenCodeEvent> {
        val stream = javaClass.classLoader!!.getResourceAsStream("opencode-v2-events/$resource")!!
        return stream.bufferedReader().useLines { lines ->
            lines.filter { it.isNotBlank() }.flatMap { translator.translate(it) }.toList()
        }
    }
}
