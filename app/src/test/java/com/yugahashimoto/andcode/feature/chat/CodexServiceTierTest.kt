package com.yugahashimoto.andcode.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The chat's fast-mode mapping ([codexServiceTier]): the user's choice becomes the `serviceTier` a
 * prompt carries. A null choice sends nothing, so the Codex layer falls back to the model's own
 * default; `"default"` is the explicit standard-speed request.
 */
class CodexServiceTierTest {
    @Test
    fun `a model without a fast tier never sends one`() {
        assertEquals(null, codexServiceTier(fastTier = null, fastMode = true))
        assertEquals(null, codexServiceTier(fastTier = null, fastMode = false))
        assertEquals(null, codexServiceTier(fastTier = null, fastMode = null))
    }

    @Test
    fun `following the model default sends nothing`() {
        assertEquals(null, codexServiceTier(fastTier = "priority", fastMode = null))
    }

    @Test
    fun `turning fast mode on sends the model's fast tier`() {
        assertEquals("priority", codexServiceTier(fastTier = "priority", fastMode = true))
    }

    @Test
    fun `turning fast mode off sends default`() {
        assertEquals("default", codexServiceTier(fastTier = "priority", fastMode = false))
    }
}
