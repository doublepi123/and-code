package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.core.api.OpenCodeAgent
import com.yugahashimoto.andcode.core.api.OpenCodeModel
import com.yugahashimoto.andcode.core.api.OpenCodeProvider
import com.yugahashimoto.andcode.core.api.ProviderCatalog
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * Codex has exactly one provider - OpenAI's own hosted models via a ChatGPT/API-key sign-in - so
 * this builds the single-provider [ProviderCatalog] the rest of the app expects from a `model/list`
 * result, verified live: `{"data":[{"id":"gpt-6-astra","displayName":"GPT-6-Astra",...}, ...]}`.
 */
object CodexModels {
    const val PROVIDER_ID = "openai"

    /** Service-tier ids that mean "fast" when the server does not name the tier "Fast". */
    private val FAST_TIER_IDS = setOf("priority", "fast")

    fun catalog(
        modelListResult: JsonObject,
        connected: Boolean,
    ): ProviderCatalog {
        val entries = modelListResult["data"] as? JsonArray ?: JsonArray(emptyList())
        val models =
            entries.mapNotNull { entry ->
                val model = entry as? JsonObject ?: return@mapNotNull null
                val id = model.string("id") ?: return@mapNotNull null
                val fastTier = fastServiceTier(model)
                id to
                    OpenCodeModel(
                        id = id,
                        providerId = PROVIDER_ID,
                        name = model.string("displayName") ?: id,
                        variants = reasoningEfforts(model),
                        codexFastServiceTier = fastTier?.first,
                        codexFastDescription = fastTier?.second,
                        codexFastByDefault = fastTier != null && model.string("defaultServiceTier") == fastTier.first,
                        codexDefaultEffort = model.string("defaultReasoningEffort")?.takeIf(String::isNotBlank),
                        codexDefaultServiceTier = model.string("defaultServiceTier")?.takeIf(String::isNotBlank),
                    )
            }.toMap()
        val provider = OpenCodeProvider(id = PROVIDER_ID, name = "OpenAI (Codex)", models = models)
        return ProviderCatalog(
            all = listOf(provider),
            connected = if (connected) listOf(PROVIDER_ID) else emptyList(),
        )
    }

    /**
     * The id of the model Codex itself would use when none is named: the one `model/list` marks
     * `isDefault`, else the first listed. Null when the list is empty.
     */
    fun defaultModelId(modelListResult: JsonObject): String? {
        val entries = (modelListResult["data"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: return null
        val marked = entries.firstOrNull { (it["isDefault"] as? JsonPrimitive)?.booleanOrNull == true }
        return (marked ?: entries.firstOrNull())?.string("id")
    }

    /** Codex has one agent - there is no separate plan/build split the way OpenCode's catalogue has. */
    fun agents(): List<OpenCodeAgent> = listOf(OpenCodeAgent(name = "codex", description = "Codex", mode = "primary", native = true))

    /**
     * Reasoning efforts in the server's own order, keyed by the effort id the chat's thinking chip
     * sends back as the turn's `effort`. Same shape as [ClaudeModels]' variants: the value is the
     * id itself, so the chip reads the key and nothing else.
     */
    private fun reasoningEfforts(model: JsonObject): Map<String, JsonElement> {
        val efforts = model["supportedReasoningEfforts"] as? JsonArray ?: return emptyMap()
        return efforts.mapNotNull { entry ->
            val effort = (entry as? JsonObject)?.string("reasoningEffort")?.takeIf(String::isNotBlank)
            effort?.let { it to JsonPrimitive(it) }
        }.toMap()
    }

    /**
     * The `(id, description)` of the fast service tier, or null when the model offers none. The tier
     * is found by its name ("Fast") or its id (`priority`, `fast`); the deprecated
     * `additionalSpeedTiers: ["fast"]` is the fallback for an older server that lists no tiers.
     */
    private fun fastServiceTier(model: JsonObject): Pair<String, String?>? {
        val tiers = model["serviceTiers"] as? JsonArray
        val tier =
            tiers?.firstNotNullOfOrNull { entry ->
                val tierObject = entry as? JsonObject ?: return@firstNotNullOfOrNull null
                val id = tierObject.string("id") ?: return@firstNotNullOfOrNull null
                val name = tierObject.string("name")
                if (name.equals("Fast", ignoreCase = true) || id in FAST_TIER_IDS) {
                    id to tierObject.string("description")
                } else {
                    null
                }
            }
        if (tier != null) return tier
        val legacy = (model["additionalSpeedTiers"] as? JsonArray)?.any { (it as? JsonPrimitive)?.contentOrNull == "fast" } == true
        return if (legacy) "fast" to null else null
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}
