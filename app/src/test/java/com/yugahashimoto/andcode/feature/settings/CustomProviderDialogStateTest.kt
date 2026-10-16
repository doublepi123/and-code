package com.yugahashimoto.andcode.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomProviderDialogStateTest {
    @Test
    fun `parseModelIdList splits on commas and newlines and drops blanks and duplicates`() {
        assertEquals(
            listOf("gpt-4o", "gpt-4o-mini", "llama3"),
            parseModelIdList(" gpt-4o, gpt-4o-mini\nllama3,,gpt-4o \n"),
        )
    }

    @Test
    fun `isBusy covers both fetching and submitting`() {
        assertFalse(CustomProviderDialogState().isBusy)
        assertTrue(CustomProviderDialogState(isFetchingModels = true).isBusy)
        assertTrue(CustomProviderDialogState(isSubmitting = true).isBusy)
    }
}
