package com.yugahashimoto.andcode.core.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * Turns one V2 SSE `data:` payload into the V1-shaped [OpenCodeEvent]s the chat, activity log and
 * schedule watcher already understand, so none of them need to know which protocol is on the wire.
 *
 * V2 has no `message.part.*` snapshots of its own: it streams `session.text.*`, `session.tool.*` and
 * friends per assistant step. The translator rebuilds the V1 part snapshots from those, and it uses
 * [OpenCodeV2Ids] for part ids so that a streamed part and the same part reloaded from
 * `GET api/session/{id}/message` are one row, not two.
 *
 * Stateful: tool calls are keyed by call id across several events, so one instance must serve one
 * SSE connection only. Never throws: a known type with a broken body becomes [OpenCodeEvent.Unknown],
 * and a payload that is not a typed JSON object becomes an empty list.
 */
class OpenCodeV2EventTranslator(
    private val json: Json,
    private val formRegistry: OpenCodeV2FormRegistry,
    /** Epoch-ms source; injectable so the liveness throttle is testable without sleeping. */
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Tool calls seen so far, so that later events for the same call keep its name, input and metadata. */
    private val toolCalls = HashMap<String, ToolCallState>()

    /** When each session last emitted a liveness delta; see [livenessThrottled]. */
    private val lastLivenessAt = HashMap<String, Long>()

    fun translate(rawData: String): List<OpenCodeEvent> {
        val envelope = runCatching { json.parseToJsonElement(rawData) as? JsonObject }.getOrNull() ?: return emptyList()
        val type = envelope.string("type") ?: return emptyList()
        return runCatching { translateEvent(type, envelope, rawData) }
            .getOrElse { listOf(OpenCodeEvent.Unknown(type, rawData)) }
    }

    private fun translateEvent(
        type: String,
        envelope: JsonObject,
        rawData: String,
    ): List<OpenCodeEvent> {
        val data = envelope.objectOrNull("data") ?: JsonObject(emptyMap())
        val envelopeDirectory = envelope.objectOrNull("location")?.string("directory")
        return when (type) {
            "server.connected" -> listOf(OpenCodeEvent.ServerConnected)
            // The step snapshot is the only place V2 names the role of an assistant message; the chat
            // needs that role to tell the server's echo of the user's own prompt apart from the reply.
            "session.step.started" -> {
                val model = data.objectOrNull("model")
                listOf(
                    OpenCodeEvent.MessageUpdated(
                        OpenCodeMessageInfo(
                            id = data.required("assistantMessageID"),
                            sessionId = data.required("sessionID"),
                            role = "assistant",
                            agent = data.string("agent"),
                            model =
                                model?.let {
                                    OpenCodeModelReference(
                                        providerId = it.required("providerID"),
                                        modelId = it.required("id"),
                                    )
                                },
                        ),
                    ),
                )
            }
            "session.step.ended" ->
                listOf(
                    OpenCodeEvent.MessageUpdated(
                        OpenCodeMessageInfo(
                            id = data.required("assistantMessageID"),
                            sessionId = data.required("sessionID"),
                            role = "assistant",
                            tokens =
                                data["tokens"]?.let { json.decodeFromJsonElement(OpenCodeSessionTokens.serializer(), it) },
                        ),
                    ),
                )
            // step.streamed repeats per LLM chunk and usage.updated per step; during a long generation with
            // no visible text they are the only sign the run is alive, so they count as liveness too.
            "session.step.streamed", "session.usage.updated" ->
                listOfNotNull(livenessThrottled(data.required("sessionID")))
            "session.step.failed" -> {
                val error = data.objectOrNull("error")
                listOf(
                    OpenCodeEvent.MessageUpdated(
                        OpenCodeMessageInfo(
                            id = data.required("assistantMessageID"),
                            sessionId = data.required("sessionID"),
                            role = "assistant",
                            error =
                                OpenCodeMessageError(
                                    name = mappedErrorName(error),
                                    data = error?.string("message")?.let { mapOf("message" to JsonPrimitive(it)) },
                                ),
                        ),
                    ),
                )
            }
            // Text and reasoning share one shape: a blank `started` snapshot, deltas, then the full
            // `ended` text, which replaces whatever the deltas accumulated.
            "session.text.started" -> listOf(partStarted(data, "text", OpenCodeV2Ids::textPartId))
            "session.text.delta" -> listOf(partDelta(data, "text", OpenCodeV2Ids::textPartId))
            "session.text.ended" -> listOf(partEnded(data, "text", OpenCodeV2Ids::textPartId))
            "session.reasoning.started" -> listOf(partStarted(data, "reasoning", OpenCodeV2Ids::reasoningPartId))
            "session.reasoning.delta" -> listOf(partDelta(data, "reasoning", OpenCodeV2Ids::reasoningPartId))
            "session.reasoning.ended" -> listOf(partEnded(data, "reasoning", OpenCodeV2Ids::reasoningPartId))
            "session.tool.input.started" -> {
                val callId = data.required("id")
                val name = data.string("name")
                toolCalls[callId] = ToolCallState(name = name)
                listOf(toolUpdate(data, callId, name, status = "pending"))
            }
            // The argument text is complete here, so the card can show the command before the tool runs.
            "session.tool.input.ended" -> {
                val callId = data.required("id")
                val call = toolCalls.getOrPut(callId) { ToolCallState(name = null) }
                call.input = data.string("text")?.let { runCatching { json.parseToJsonElement(it) }.getOrNull() } ?: call.input
                listOf(toolUpdate(data, callId, call.name, status = "pending", call = call))
            }
            "session.tool.called" -> {
                val callId = data.required("id")
                val call = toolCalls.getOrPut(callId) { ToolCallState(name = null) }
                call.input = data["input"] ?: call.input
                listOf(toolUpdate(data, callId, call.name, status = "running", call = call))
            }
            "session.tool.progress" -> {
                val callId = data.required("id")
                val call = toolCalls.getOrPut(callId) { ToolCallState(name = null) }
                call.metadata = data["metadata"] ?: call.metadata
                listOf(toolUpdate(data, callId, call.name, status = "running", call = call))
            }
            "session.tool.success" -> {
                val callId = data.required("id")
                val call = toolCalls.remove(callId) ?: ToolCallState(name = null)
                listOf(
                    toolUpdate(
                        data,
                        callId,
                        call.name,
                        status = "completed",
                        call = call,
                        extra =
                            listOfNotNull(
                                joinedText(data["content"])?.let { "output" to JsonPrimitive(it) },
                                (data["metadata"] ?: call.metadata)?.let { "metadata" to it },
                            ).toMap(),
                    ),
                )
            }
            "session.tool.failed" -> {
                val callId = data.required("id")
                val call = toolCalls.remove(callId) ?: ToolCallState(name = null)
                val message = data.objectOrNull("error")?.string("message")
                listOf(
                    toolUpdate(
                        data,
                        callId,
                        call.name,
                        status = "error",
                        call = call,
                        extra = listOfNotNull(message?.let { "error" to JsonPrimitive(it) }).toMap(),
                    ),
                )
            }
            "session.execution.started" ->
                listOf(OpenCodeEvent.SessionStatusChanged(data.required("sessionID"), "busy"))
            // A manual compaction is a run of its own with no text to show, so it is announced as busy
            // like any run. Its deltas are the only sign it is alive, and they are sent as liveness only
            // (see [livenessDelta]): the chat ignores them, the stall watchdog counts them.
            "session.compaction.started" -> listOf(OpenCodeEvent.SessionStatusChanged(data.required("sessionID"), "busy"))
            "session.compaction.delta" -> listOfNotNull(livenessThrottled(data.required("sessionID")))
            // Nothing extra: compaction failure and success are followed by session.execution.* which settles idle.
            "session.compaction.failed" -> emptyList()
            // Emitted as SessionIdle rather than SessionStatusChanged("idle"): the schedule watcher
            // completes a run only on SessionIdle, and the activity repository fires its idle
            // notification from it too.
            "session.execution.succeeded" -> listOf(OpenCodeEvent.SessionIdle(data.required("sessionID")))
            // An interruption is a stop, not a completion. V1 reports a stop as a MessageAbortedError
            // session.error followed by idle; the activity repository mutes the session on that error,
            // so the idle does not raise a "completed" notification. V2 sends no error here, so one
            // is synthesised to get the same treatment, whichever device asked for the stop.
            "session.execution.interrupted" -> {
                val sessionId = data.required("sessionID")
                listOf(
                    OpenCodeEvent.SessionError(sessionId, message = null, name = ABORT_ERROR_NAME),
                    OpenCodeEvent.SessionIdle(sessionId),
                )
            }
            "session.execution.failed" -> {
                val sessionId = data.required("sessionID")
                val error = data.objectOrNull("error")
                // The error comes before the idle: the activity repository mutes the session on the error,
                // which stops the idle that follows from raising a completion notification for a failed run.
                listOf(
                    OpenCodeEvent.SessionError(sessionId, error?.string("message"), mappedErrorName(error)),
                    OpenCodeEvent.SessionIdle(sessionId),
                )
            }
            "permission.asked" ->
                listOf(
                    OpenCodeEvent.PermissionAsked(
                        PermissionRequest(
                            id = data.required("id"),
                            sessionId = data.required("sessionID"),
                            permission = data.required("action"),
                            patterns = data.stringList("resources"),
                            metadata =
                                listOfNotNull(
                                    data["save"]?.let { "save" to it },
                                    data["source"]?.let { "source" to it },
                                ).toMap(),
                        ),
                    ),
                )
            "permission.replied" ->
                listOf(
                    OpenCodeEvent.PermissionReplied(
                        sessionId = data.required("sessionID"),
                        requestId = data.required("requestID"),
                    ),
                )
            // Remembered before it is announced, so the answer can be posted without a second lookup.
            // V2 forms replace V1 questions, so they reach the UI as questions.
            "form.created" -> {
                val form = json.decodeFromJsonElement(V2Form.serializer(), data.getValue("form"))
                formRegistry.remember(form)
                listOf(OpenCodeEvent.QuestionAsked(form.toQuestionRequest(directory = envelopeDirectory)))
            }
            // No event on the chat side removes a card that was answered or cancelled elsewhere, and V1
            // has the same gap for questions. Forgetting the form keeps the registry from growing.
            "form.replied", "form.cancelled" -> {
                formRegistry.forget(data.required("id"))
                emptyList()
            }
            // `session.created` and `session.forked` are dropped on purpose. Neither names a subagent's
            // parent: `parentID` on a fork is the session it was forked FROM, which is not the parent the
            // activity repository needs. Emitting it as SessionCreated would cache the source as a
            // subagent parent and mute the wrong session. The repository's REST lookup
            // (`GET api/session/{id}`) is the one reliable source of a parent, so it is left to do that.
            "session.created", "session.forked" -> emptyList()
            in IgnoredTypes -> emptyList()
            else -> listOf(OpenCodeEvent.Unknown(type, rawData))
        }
    }

    /**
     * A liveness-only [OpenCodeEvent.MessagePartDelta] for [sessionId], at most one per
     * [LIVENESS_INTERVAL_MS]. The stream can emit `step.streamed` many times a second; the stall
     * watchdog only needs a heartbeat, and each delta costs a state update in the repository.
     *
     * The delta names a field the chat does not render, so it changes nothing on screen. It is a
     * delta rather than a status change because the watchdog only counts message, part, permission,
     * question, idle and error events as progress (see `provesRunProgress`).
     */
    private fun livenessThrottled(sessionId: String): OpenCodeEvent? {
        val now = clock()
        val last = lastLivenessAt[sessionId]
        if (last != null && now - last < LIVENESS_INTERVAL_MS) return null
        lastLivenessAt[sessionId] = now
        return OpenCodeEvent.MessagePartDelta(
            sessionId = sessionId,
            messageId = "",
            partId = "",
            field = LIVENESS_FIELD,
            delta = "",
        )
    }

    private fun partStarted(
        data: JsonObject,
        type: String,
        partId: (String, Int) -> String,
    ): OpenCodeEvent {
        val messageId = data.required("assistantMessageID")
        return OpenCodeEvent.MessagePartUpdated(
            OpenCodePart(
                id = partId(messageId, data.ordinal()),
                sessionId = data.required("sessionID"),
                messageId = messageId,
                type = type,
                text = "",
            ),
        )
    }

    private fun partDelta(
        data: JsonObject,
        field: String,
        partId: (String, Int) -> String,
    ): OpenCodeEvent {
        val messageId = data.required("assistantMessageID")
        return OpenCodeEvent.MessagePartDelta(
            sessionId = data.required("sessionID"),
            messageId = messageId,
            partId = partId(messageId, data.ordinal()),
            field = field,
            delta = data.required("delta"),
        )
    }

    private fun partEnded(
        data: JsonObject,
        type: String,
        partId: (String, Int) -> String,
    ): OpenCodeEvent {
        val messageId = data.required("assistantMessageID")
        return OpenCodeEvent.MessagePartUpdated(
            OpenCodePart(
                id = partId(messageId, data.ordinal()),
                sessionId = data.required("sessionID"),
                messageId = messageId,
                type = type,
                text = data.required("text"),
            ),
        )
    }

    /**
     * A tool part snapshot. The part id is the call id, which is also what the V2 message mapper uses,
     * so a streamed tool and its reloaded copy are the same row.
     */
    private fun toolUpdate(
        data: JsonObject,
        callId: String,
        name: String?,
        status: String,
        call: ToolCallState? = null,
        extra: Map<String, JsonElement> = emptyMap(),
    ): OpenCodeEvent {
        val state = linkedMapOf<String, JsonElement>("status" to JsonPrimitive(status))
        call?.input?.let { state["input"] = it }
        call?.metadata?.let { state["metadata"] = it }
        state.putAll(extra)
        return OpenCodeEvent.MessagePartUpdated(
            OpenCodePart(
                id = callId,
                sessionId = data.required("sessionID"),
                messageId = data.required("assistantMessageID"),
                type = "tool",
                tool = name,
                callID = callId,
                state = state,
            ),
        )
    }

    /** Facts about one tool call that arrive on different events. */
    private class ToolCallState(
        val name: String?,
        var input: JsonElement? = null,
        var metadata: JsonElement? = null,
    )

    /**
     * Seeds a tool call the client already knows about, so that later `tool.progress` / `tool.success`
     * events after a reconnect keep the name and input. Without it, a reconnected stream would rebuild
     * the part with a null tool name and no input, which overwrites what the chat already shows.
     */
    fun seedTool(
        callId: String,
        name: String,
        input: JsonElement?,
    ) {
        toolCalls[callId] = ToolCallState(name = name, input = input)
    }

    private companion object {
        /** Minimum gap between two liveness deltas for one session. */
        const val LIVENESS_INTERVAL_MS = 5_000L

        /** Field name of the liveness-only delta. Not `text` or `reasoning`, so the chat never renders it. */
        const val LIVENESS_FIELD = "liveness"

        /**
         * Events that carry nothing the app acts on and that repeat often, mostly once per step or per
         * shell command. They are dropped rather than sent as [OpenCodeEvent.Unknown], because every
         * Unknown is written to the activity log as "Unsupported event". Rename and move are here too:
         * they carry a partial session, and mapping them to SessionUpdated would overwrite a known
         * parent with null in the activity repository.
         */
        val IgnoredTypes =
            setOf(
                "session.inbox.enqueued",
                "session.inbox.delivered",
                "session.instructions.updated",
                "session.shell.started",
                "session.shell.ended",
                "session.viewed",
                "session.renamed",
                "session.moved",
                "session.deleted",
                "session.agent.selected",
                "session.model.selected",
                "shell.created",
                "shell.exited",
                "project.updated",
                "vcs.branch.updated",
                "agent.updated",
                "model.updated",
                "provider.updated",
                "integration.updated",
                "skill.updated",
                "command.updated",
                "reference.updated",
                "plugin.updated",
                "websearch.updated",
            )
    }
}

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.objectOrNull(key: String): JsonObject? = this[key] as? JsonObject

/** A string field that every mapped event needs; a missing one fails the mapping and becomes Unknown. */
private fun JsonObject.required(key: String): String =
    string(key) ?: throw IllegalArgumentException("V2 event is missing string field '$key'")

private fun JsonObject.ordinal(): Int = (this["ordinal"] as? JsonPrimitive)?.intOrNull ?: 0

private fun JsonObject.stringList(key: String): List<String> =
    (this[key] as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { primitive -> primitive.isString }?.content }
        .orEmpty()

/**
 * The error name the app should see for a V2 error: a cancelled run becomes the V1 abort name, so it
 * is treated as a decision rather than a failure everywhere the name is checked.
 */
private fun mappedErrorName(error: JsonObject?): String? {
    val type = error?.string("type") ?: return null
    return if (type == V2_ABORTED_ERROR_TYPE) ABORT_ERROR_NAME else type
}

private const val ABORT_ERROR_NAME = "MessageAbortedError"
private const val V2_ABORTED_ERROR_TYPE = "aborted"

/** Joins the text items of a tool result; other content kinds carry nothing the chat renders. */
private fun joinedText(content: JsonElement?): String? =
    (content as? JsonArray)
        ?.mapNotNull { item -> (item as? JsonObject)?.takeIf { it.string("type") == "text" }?.string("text") }
        ?.joinToString("")
