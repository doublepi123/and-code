package com.yugahashimoto.andcode.runtime.local

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Lists the model ids an OpenAI-compatible endpoint serves, via its `GET <baseURL>/models`.
 *
 * Typing every model id of a custom provider by hand is error-prone, and gateways that proxy many
 * vendors expose dozens of them. The response shape is OpenAI's `{"data":[{"id":"..."}]}`; a bare
 * array and a `models` field are accepted too, since several self-hosted servers answer that way.
 */
class CustomProviderModelFetcher(
    private val client: OkHttpClient = OkHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    suspend fun fetch(
        baseUrl: String,
        apiKey: String?,
    ): List<String> =
        withContext(Dispatchers.IO) {
            val url = modelsUrl(baseUrl)
            val request =
                Request.Builder()
                    .url(url)
                    .header("Accept", "application/json")
                    .apply {
                        apiKey?.trim()?.takeIf(String::isNotEmpty)?.let { header("Authorization", "Bearer $it") }
                    }
                    .get()
                    .build()
            client.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "HTTP ${response.code} from $url" }
                parseModelIds(response.body?.string().orEmpty(), json)
            }
        }

    companion object {
        /** `<baseURL>/models`, tolerating a trailing slash; fails on anything that is not an http(s) URL. */
        fun modelsUrl(baseUrl: String): String {
            val trimmed = baseUrl.trim().trimEnd('/')
            requireNotNull(trimmed.toHttpUrlOrNull()) { "Invalid base URL: $baseUrl" }
            return "$trimmed/models"
        }

        fun parseModelIds(
            body: String,
            json: Json = Json { ignoreUnknownKeys = true },
        ): List<String> {
            val root = runCatching { json.parseToJsonElement(body) }.getOrElse { throw IllegalStateException("Response is not JSON", it) }
            val entries: JsonArray =
                when (root) {
                    is JsonArray -> root
                    is JsonObject -> (root["data"] as? JsonArray) ?: (root["models"] as? JsonArray) ?: JsonArray(emptyList())
                    else -> throw IllegalStateException("Expected a model list object or array")
                }
            return entries
                .mapNotNull(::modelId)
                .map(String::trim)
                .filter(String::isNotEmpty)
                .distinct()
                .sorted()
        }

        private fun modelId(entry: JsonElement): String? =
            when (entry) {
                is JsonPrimitive -> entry.contentOrNull
                is JsonObject -> (entry["id"] as? JsonPrimitive)?.contentOrNull ?: (entry["name"] as? JsonPrimitive)?.contentOrNull
                else -> null
            }
    }
}
