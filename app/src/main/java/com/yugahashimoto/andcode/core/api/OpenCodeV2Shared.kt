package com.yugahashimoto.andcode.core.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Part ids shared by the V2 REST mapper and the V2 event translator. The translator and the
 * message loader must agree on these, otherwise a streamed part and the persisted copy of the same
 * part would be two different rows in the chat.
 */
object OpenCodeV2Ids {
    fun textPartId(
        messageId: String,
        ordinal: Int,
    ) = "$messageId-text-$ordinal"

    fun reasoningPartId(
        messageId: String,
        ordinal: Int,
    ) = "$messageId-reasoning-$ordinal"
}

/** A selectable value of a V2 form field (`Form.Info` option). */
@Serializable
data class V2FormOption(
    val value: String,
    val label: String? = null,
    val description: String? = null,
)

/** One question inside a V2 form. [type] is the raw V2 field type: string, multiselect, boolean, number, integer. */
@Serializable
data class V2FormField(
    val key: String,
    val type: String,
    val title: String? = null,
    val description: String? = null,
    val options: List<V2FormOption> = emptyList(),
    val custom: Boolean? = null,
    val placeholder: String? = null,
    val required: Boolean? = null,
)

/** A V2 form: the replacement for V1 question requests. */
@Serializable
data class V2Form(
    val id: String,
    @SerialName("sessionID") val sessionId: String,
    val title: String? = null,
    val fields: List<V2FormField> = emptyList(),
)

/**
 * Maps a V2 form onto the app's existing [QuestionRequest], so the question UI works unchanged.
 * Each field becomes one prompt; the prompt text prefers the field's description, since that is
 * the sentence a user reads, and falls back to the title or key.
 */
fun V2Form.toQuestionRequest(directory: String?): QuestionRequest =
    QuestionRequest(
        id = id,
        sessionId = sessionId,
        questions =
            fields.map { field ->
                QuestionPrompt(
                    question = field.description ?: field.title ?: field.key,
                    header = field.title,
                    options =
                        field.options.map { option ->
                            QuestionOption(
                                label = option.label ?: option.value,
                                description = option.description,
                            )
                        },
                    placeholder = field.placeholder,
                    multiple = field.type == "multiselect",
                    custom = field.custom ?: field.options.isEmpty(),
                )
            },
        directory = directory,
    )

/**
 * Remembers forms seen on the event stream or from `GET api/form`, so an answer can be posted
 * without a second lookup. The translator writes here from the SSE thread while the UI thread
 * reads, hence the lock.
 */
class OpenCodeV2FormRegistry {
    private val lock = Any()
    private val forms = HashMap<String, V2Form>()

    fun remember(form: V2Form) {
        synchronized(lock) { forms[form.id] = form }
    }

    fun get(id: String): V2Form? = synchronized(lock) { forms[id] }

    fun forget(id: String) {
        synchronized(lock) { forms.remove(id) }
    }
}
