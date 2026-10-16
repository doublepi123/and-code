package com.yugahashimoto.andcode.runtime.remote

import com.yugahashimoto.andcode.data.connection.ConnectionProfile
import com.yugahashimoto.andcode.feature.settings.McpViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteRuntimeTargetCapabilitiesTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun target() =
        RemoteRuntimeTarget(
            ConnectionProfile(
                id = "remote",
                name = "Remote",
                baseUrl = server.url("/").toString(),
                username = "opencode",
                password = "pw",
                allowInsecureLan = true,
            ),
        )

    @Test
    fun `a V1 server keeps message edits, config editing and MCP OAuth`() =
        runBlocking {
            server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("<html/>"))
            server.enqueue(MockResponse().setBody("""{"healthy":true,"version":"1.18.5"}"""))
            val target = target()

            target.connect().getOrThrow()

            val capabilities = target.capabilities
            assertTrue(capabilities.editMessages)
            assertTrue(capabilities.configEditing)
            assertTrue(capabilities.mcpOAuth)
        }

    @Test
    fun `a V2 server turns off what its API does not offer`() =
        runBlocking {
            server.enqueue(MockResponse().setBody("""{"version":"2.0.28","pid":1,"urls":[],"paths":{},"capabilities":{}}"""))
            val target = target()

            target.connect().getOrThrow()

            val capabilities = target.capabilities
            assertFalse(capabilities.editMessages)
            assertFalse(capabilities.configEditing)
            assertFalse(capabilities.mcpOAuth)
            // What V2 does support is unchanged.
            assertTrue(capabilities.permissions)
            assertTrue(capabilities.diffCapable)
        }

    @Test
    fun `the MCP screen hides OAuth actions on a V2 server`() {
        // The view model loads on the main dispatcher; run it eagerly on this thread.
        Dispatchers.setMain(Dispatchers.Unconfined)
        try {
            server.enqueue(MockResponse().setBody("""{"version":"2.0.28","pid":1,"urls":[],"paths":{},"capabilities":{}}"""))
            server.enqueue(MockResponse().setBody("""{"data":[]}"""))
            val target = target()

            val viewModel = McpViewModel({ target })
            val deadline = System.currentTimeMillis() + 5_000
            while (viewModel.state.value.isLoading || server.requestCount < 2) {
                check(System.currentTimeMillis() < deadline) { "MCP list never loaded" }
                Thread.sleep(10)
            }

            assertFalse(viewModel.state.value.supportsOAuth)
        } finally {
            Dispatchers.resetMain()
        }
    }
}
