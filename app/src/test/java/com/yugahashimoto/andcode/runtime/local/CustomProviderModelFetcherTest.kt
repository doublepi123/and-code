package com.yugahashimoto.andcode.runtime.local

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CustomProviderModelFetcherTest {
    private lateinit var server: MockWebServer
    private val fetcher = CustomProviderModelFetcher(OkHttpClient())

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `fetch reads OpenAI style data ids and sends the bearer key`() {
        server.enqueue(
            MockResponse().setBody("""{"object":"list","data":[{"id":"b-model"},{"id":"a-model"},{"id":"b-model"}]}"""),
        )

        val models = runBlocking { fetcher.fetch(server.url("/v1/").toString(), " sk-test-key ") }

        assertEquals(listOf("a-model", "b-model"), models)
        val request = server.takeRequest()
        assertEquals("/v1/models", request.path)
        assertEquals("Bearer sk-test-key", request.getHeader("Authorization"))
    }

    @Test
    fun `fetch omits the authorization header without a key`() {
        server.enqueue(MockResponse().setBody("""{"data":[{"id":"llama3"}]}"""))

        val models = runBlocking { fetcher.fetch(server.url("/v1").toString(), "  ") }

        assertEquals(listOf("llama3"), models)
        assertNull(server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `fetch fails on an http error`() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"unauthorized"}"""))

        val error =
            assertThrows(IllegalStateException::class.java) {
                runBlocking { fetcher.fetch(server.url("/v1").toString(), "bad") }
            }
        assertTrue(error.message.orEmpty().contains("401"))
    }

    @Test
    fun `modelsUrl rejects a non http url`() {
        assertThrows(IllegalArgumentException::class.java) { CustomProviderModelFetcher.modelsUrl("not a url") }
        assertEquals("https://api.example.com/v1/models", CustomProviderModelFetcher.modelsUrl(" https://api.example.com/v1/ "))
    }

    @Test
    fun `parseModelIds accepts bare arrays and models fields`() {
        assertEquals(listOf("x", "y"), CustomProviderModelFetcher.parseModelIds("""["y","x"," "]"""))
        assertEquals(listOf("m1"), CustomProviderModelFetcher.parseModelIds("""{"models":[{"name":"m1"}]}"""))
        assertEquals(emptyList<String>(), CustomProviderModelFetcher.parseModelIds("""{"data":"nope"}"""))
    }

    @Test
    fun `parseModelIds rejects non json`() {
        assertThrows(IllegalStateException::class.java) { CustomProviderModelFetcher.parseModelIds("<html>") }
    }

    @Test
    fun `parseModelIds rejects scalar responses rather than treating them as empty lists`() {
        listOf("null", "true", "42", "\"error\"").forEach { body ->
            assertThrows(IllegalStateException::class.java) { CustomProviderModelFetcher.parseModelIds(body) }
        }
    }
}
