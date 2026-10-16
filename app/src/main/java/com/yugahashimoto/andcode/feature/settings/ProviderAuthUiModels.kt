package com.yugahashimoto.andcode.feature.settings

import com.yugahashimoto.andcode.core.api.ProviderAuthAuthorization
import com.yugahashimoto.andcode.core.api.ProviderAuthMethod

data class ProviderAuthDialogState(
    val providerId: String,
    val providerName: String,
    val methods: List<ProviderAuthMethod>,
    val methodIndex: Int? = null,
    val inputs: Map<String, String> = emptyMap(),
    val apiKey: String = "",
    val authorization: ProviderAuthAuthorization? = null,
    val isSubmitting: Boolean = false,
    val failed: Boolean = false,
    val error: String? = null,
) {
    val selectedMethod: ProviderAuthMethod?
        get() = methodIndex?.let(methods::getOrNull)

    val visiblePrompts
        get() = selectedMethod?.prompts.orEmpty().filter { it.isVisible(inputs) }

    val promptsComplete: Boolean
        get() =
            visiblePrompts.all { prompt ->
                when (prompt.type) {
                    "text", "select" -> !inputs[prompt.key].isNullOrBlank()
                    else -> true
                }
            }
}

enum class ProviderAuthNotice {
    CONNECTED,
    DISCONNECTED,
}

/** Draft state for the "add custom provider" dialog: an OpenAI-compatible endpoint the user is registering by hand. */
data class CustomProviderDialogState(
    val id: String = "",
    val name: String = "",
    val baseUrl: String = "",
    val models: String = "",
    /** Only used to call `<baseURL>/models`; it is handed to the API-key step afterwards, never stored here. */
    val apiKey: String = "",
    /** True when the dialog edits an already registered provider, whose id must not change. */
    val editing: Boolean = false,
    /** Whether a stored credential exists for [id], so fetching works without re-typing the key. */
    val hasStoredApiKey: Boolean = false,
    val isFetchingModels: Boolean = false,
    val fetchedModelCount: Int? = null,
    val fetchError: String? = null,
    val isSubmitting: Boolean = false,
    val error: String? = null,
) {
    val isBusy: Boolean get() = isSubmitting || isFetchingModels
}

/** Splits the comma- or newline-separated model field into distinct, trimmed ids. */
internal fun parseModelIdList(raw: String): List<String> = raw.split(',', '\n').map(String::trim).filter(String::isNotEmpty).distinct()
