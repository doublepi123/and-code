package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.core.api.OpenCodeModel
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the `turn/start` params [codexTurnStartParams] builds and the per-turn resolution
 * [resolveCodexTurnSettings]. The builder writes only the values it is given: a blank or null
 * `effort` or `serviceTier` is omitted. Callers resolve first, so every real turn states both.
 */
class CodexTurnStartParamsTest {
    private fun params(
        effort: String? = null,
        serviceTier: String? = null,
        model: String? = "gpt-6-astra",
    ): JsonObject = codexTurnStartParams("thr_1", "hello", model, effort, serviceTier, emptyList())

    @Test
    fun `effort and service tier are sent when set`() {
        val result = params(effort = "high", serviceTier = "priority")

        assertEquals("high", result.getValue("effort").jsonPrimitive.content)
        assertEquals("priority", result.getValue("serviceTier").jsonPrimitive.content)
    }

    @Test
    fun `effort and service tier are omitted when unset`() {
        val result = params()

        assertFalse(result.containsKey("effort"))
        assertFalse(result.containsKey("serviceTier"))
    }

    @Test
    fun `blank effort and service tier are omitted`() {
        val result = params(effort = "  ", serviceTier = "")

        assertFalse(result.containsKey("effort"))
        assertFalse(result.containsKey("serviceTier"))
    }

    @Test
    fun `default is passed through to force standard speed`() {
        val result = params(serviceTier = "default")

        assertEquals("default", result.getValue("serviceTier").jsonPrimitive.content)
    }

    @Test
    fun `the thread, input and model are always present`() {
        val result = params(effort = "low")

        assertEquals("thr_1", result.getValue("threadId").jsonPrimitive.content)
        assertTrue(result.containsKey("input"))
        assertEquals("gpt-6-astra", result.getValue("model").jsonPrimitive.content)
    }

    @Test
    fun `a blank model is omitted like the other optional fields`() {
        val result = params(model = " ")

        assertFalse(result.containsKey("model"))
    }

    private val model =
        OpenCodeModel(
            id = "gpt-6-astra",
            providerId = CodexModels.PROVIDER_ID,
            codexDefaultEffort = "medium",
            codexDefaultServiceTier = "priority",
        )

    @Test
    fun `no variant falls back to the model's default effort`() {
        assertEquals("medium", resolveCodexTurnSettings(null, null, model).effort)
    }

    @Test
    fun `an explicit variant wins over the model default`() {
        assertEquals("ultra", resolveCodexTurnSettings("ultra", null, model).effort)
    }

    @Test
    fun `no requested tier falls back to the model's default tier`() {
        assertEquals("priority", resolveCodexTurnSettings(null, null, model).serviceTier)
    }

    @Test
    fun `no requested tier and no model default sends default`() {
        val plain = OpenCodeModel(id = "plain", providerId = CodexModels.PROVIDER_ID)

        assertEquals(DEFAULT_SERVICE_TIER, resolveCodexTurnSettings(null, null, plain).serviceTier)
        assertNull(resolveCodexTurnSettings(null, null, plain).effort)
    }

    @Test
    fun `an explicit default or tier passes through unchanged`() {
        assertEquals("default", resolveCodexTurnSettings(null, "default", model).serviceTier)
        assertEquals("priority", resolveCodexTurnSettings(null, "priority", model).serviceTier)
    }

    @Test
    fun `an unknown model omits the effort and sends default`() {
        val settings = resolveCodexTurnSettings(null, null, null)

        assertNull(settings.effort)
        assertEquals("default", settings.serviceTier)
    }

    @Test
    fun `an unknown model still honours an explicit variant and tier`() {
        val settings = resolveCodexTurnSettings("high", "priority", null)

        assertEquals("high", settings.effort)
        assertEquals("priority", settings.serviceTier)
    }
}
