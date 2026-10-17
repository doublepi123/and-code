package com.yugahashimoto.andcode.runtime.local

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `modelListJson` is the real `model/list` result from a live, unauthenticated codex app-server (0.155.1). */
class CodexModelsTest {
    private val json = Json { ignoreUnknownKeys = true }

    private val modelListJson =
        """{"data":[{"id":"gpt-6-astra","model":"gpt-6-astra","displayName":"GPT-6-Astra",
            "description":"Our most capable model for complex, demanding work."}]}"""

    @Test
    fun `builds a single openai provider from model list`() {
        val catalog = CodexModels.catalog(json.parseToJsonElement(modelListJson).jsonObject, connected = true)

        val provider = catalog.all.single()
        assertEquals(CodexModels.PROVIDER_ID, provider.id)
        assertTrue(provider.models.containsKey("gpt-6-astra"))
        assertEquals("GPT-6-Astra", provider.models.getValue("gpt-6-astra").name)
        assertEquals(listOf(CodexModels.PROVIDER_ID), catalog.connected)
    }

    @Test
    fun `an unauthenticated catalog reports the provider as not connected`() {
        val catalog = CodexModels.catalog(json.parseToJsonElement(modelListJson).jsonObject, connected = false)

        assertTrue(catalog.connected.isEmpty())
    }

    /** Shape copied from a live `codex app-server` 0.163.0 `model/list`. */
    private val effortAndFastJson =
        """{"data":[{"id":"gpt-6-astra","model":"gpt-6-astra","displayName":"GPT-6-Astra",
            "supportedReasoningEfforts":[
              {"reasoningEffort":"low","description":"Fast responses"},
              {"reasoningEffort":"medium","description":"Balanced"},
              {"reasoningEffort":"high","description":"Thorough"},
              {"reasoningEffort":"xhigh","description":"Deepest"},
              {"reasoningEffort":"max","description":"Maximum"},
              {"reasoningEffort":"ultra","description":"Ultra"}],
            "defaultReasoningEffort":"medium",
            "serviceTiers":[{"id":"priority","name":"Fast","description":"1.5x speed, increased usage"}],
            "defaultServiceTier":"priority",
            "additionalSpeedTiers":["fast"]}]}"""

    @Test
    fun `reasoning efforts become variants in the server's order`() {
        val catalog = CodexModels.catalog(json.parseToJsonElement(effortAndFastJson).jsonObject, connected = true)

        val model = catalog.all.single().models.getValue("gpt-6-astra")
        assertEquals(listOf("low", "medium", "high", "xhigh", "max", "ultra"), model.variants.keys.toList())
        assertEquals("low", (model.variants.getValue("low") as kotlinx.serialization.json.JsonPrimitive).content)
    }

    @Test
    fun `a model with no reasoning efforts has no variants`() {
        val catalog = CodexModels.catalog(json.parseToJsonElement(modelListJson).jsonObject, connected = true)

        assertTrue(catalog.all.single().models.getValue("gpt-6-astra").variants.isEmpty())
    }

    @Test
    fun `the priority tier named Fast is the fast tier and is on by default`() {
        val catalog = CodexModels.catalog(json.parseToJsonElement(effortAndFastJson).jsonObject, connected = true)

        val model = catalog.all.single().models.getValue("gpt-6-astra")
        assertEquals("priority", model.codexFastServiceTier)
        assertEquals("1.5x speed, increased usage", model.codexFastDescription)
        assertTrue(model.codexFastByDefault)
    }

    @Test
    fun `a model that does not default to fast has the tier but is not fast by default`() {
        val notDefault = effortAndFastJson.replace(""""defaultServiceTier":"priority",""", """"defaultServiceTier":null,""")
        val catalog = CodexModels.catalog(json.parseToJsonElement(notDefault).jsonObject, connected = true)

        val model = catalog.all.single().models.getValue("gpt-6-astra")
        assertEquals("priority", model.codexFastServiceTier)
        assertFalse(model.codexFastByDefault)
    }

    @Test
    fun `the model's default effort and default tier are recorded`() {
        val catalog = CodexModels.catalog(json.parseToJsonElement(effortAndFastJson).jsonObject, connected = true)

        val model = catalog.all.single().models.getValue("gpt-6-astra")
        assertEquals("medium", model.codexDefaultEffort)
        assertEquals("priority", model.codexDefaultServiceTier)
    }

    @Test
    fun `a model that names no default tier records none`() {
        val catalog = CodexModels.catalog(json.parseToJsonElement(modelListJson).jsonObject, connected = true)

        val model = catalog.all.single().models.getValue("gpt-6-astra")
        assertNull(model.codexDefaultEffort)
        assertNull(model.codexDefaultServiceTier)
    }

    @Test
    fun `the default model is the one marked isDefault, else the first`() {
        val marked =
            """{"data":[{"id":"a"},{"id":"b","isDefault":true},{"id":"c"}]}"""
        val unmarked = """{"data":[{"id":"a"},{"id":"b"}]}"""

        assertEquals("b", CodexModels.defaultModelId(json.parseToJsonElement(marked).jsonObject))
        assertEquals("a", CodexModels.defaultModelId(json.parseToJsonElement(unmarked).jsonObject))
        assertNull(CodexModels.defaultModelId(json.parseToJsonElement("""{"data":[]}""").jsonObject))
    }

    @Test
    fun `a model without a fast tier has no fast fields`() {
        val catalog = CodexModels.catalog(json.parseToJsonElement(modelListJson).jsonObject, connected = true)

        val model = catalog.all.single().models.getValue("gpt-6-astra")
        assertNull(model.codexFastServiceTier)
        assertNull(model.codexFastDescription)
        assertFalse(model.codexFastByDefault)
    }

    @Test
    fun `the legacy additionalSpeedTiers fast entry is used when no tier is listed`() {
        val legacy =
            """{"data":[{"id":"m","displayName":"M","additionalSpeedTiers":["fast"]}]}"""
        val catalog = CodexModels.catalog(json.parseToJsonElement(legacy).jsonObject, connected = true)

        assertEquals("fast", catalog.all.single().models.getValue("m").codexFastServiceTier)
    }
}
