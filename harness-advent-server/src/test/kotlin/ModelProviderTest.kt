package com.harnessadvent

import com.harnessadvent.adapters.OpenAiCompatibleModelProvider
import com.harnessadvent.adapters.GitHubRepositoryPolicy
import com.harnessadvent.bootstrap.McpServerConnection
import com.harnessadvent.bootstrap.HarnessConfig
import com.harnessadvent.domain.ModelCompletionRequest
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.Properties
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ModelProviderTest {
    @Test
    fun `reads allowed projects from HARNESS_ALLOWED_PROJECTS`() {
        val firstProject = Files.createTempDirectory("harness-project")
        val secondProject = Files.createTempDirectory("harness-project")

        val config = HarnessConfig.fromProperties(Properties().apply {
            setProperty("HARNESS_ALLOWED_PROJECTS", "$firstProject, $secondProject")
        })

        assertEquals(
            setOf(firstProject.toAbsolutePath().normalize(), secondProject.toAbsolutePath().normalize()),
            config.allowedProjectPaths,
        )
    }

    @Test
    fun `GitHub MCP requires read only mode and exactly one repository`() {
        assertFailsWith<IllegalArgumentException> {
            HarnessConfig.fromProperties(Properties().apply {
                setProperty("mcp.servers", "github")
                setProperty("mcp.github.command", "docker")
                setProperty("mcp.github.arguments", "run")
                setProperty("mcp.github.readOnly", "true")
                setProperty("mcp.github.allowedRepositories", "one/repository,two/repository")
                setProperty("mcp.github.allowedTools", "get_file_contents")
                setProperty("mcp.github.env.GITHUB_READ_ONLY", "1")
            })
        }
    }

    @Test
    fun `reads the cloud code review auto approval profile list`() {
        val config = HarnessConfig.fromProperties(Properties().apply {
            setProperty("codeReview.autoApproveContextProfiles", "deepseek")
        })

        assertEquals(setOf("deepseek"), config.codeReviewAutoApprovedContextProfiles)
    }

    @Test
    fun `reads UTF-8 MCP name from local properties`() {
        val configFile = Files.createTempFile("harness", ".properties")
        Files.writeString(
            configFile,
            """
            mcp.servers=youtrack
            mcp.youtrack.name=YouTrack (только чтение)
            mcp.youtrack.command=node
            mcp.youtrack.arguments=/tmp/youtrack.mjs
            mcp.youtrack.readOnly=true
            mcp.youtrack.allowedTools=youtrack_get_issue
            """.trimIndent(),
        )

        val config = HarnessConfig.fromFile(configFile)

        assertEquals("YouTrack (только чтение)", config.mcpServers.single().name)
    }

    @Test
    fun `GitHub MCP injects the configured repository and rejects another one`() {
        val connection = McpServerConnection(
            id = "github", name = "GitHub", command = "docker", arguments = listOf("run"), environment = emptyMap(),
            readOnly = true, allowedRepositories = setOf("owner/repository"), allowedTools = setOf("get_file_contents"), timeoutSeconds = 30,
        )

        val safeArguments = GitHubRepositoryPolicy.restrict(connection, JsonObject(mapOf("path" to JsonPrimitive("README.md"))))

        assertEquals("owner", safeArguments["owner"]?.let { (it as JsonPrimitive).content })
        assertEquals("repository", safeArguments["repo"]?.let { (it as JsonPrimitive).content })
        assertFailsWith<IllegalArgumentException> {
            GitHubRepositoryPolicy.restrict(connection, JsonObject(mapOf("owner" to JsonPrimitive("other"))))
        }
    }

    @Test
    fun `reads model token from local properties and calls OpenAI-compatible API`() {
        runBlocking {
            var authorization = ""
            var requestPath = ""
            val server = HttpServer.create(InetSocketAddress(0), 0).apply {
                createContext("/v1/chat/completions") { exchange ->
                    authorization = exchange.requestHeaders.getFirst("Authorization").orEmpty()
                    requestPath = exchange.requestURI.path
                    val response = """{"choices":[{"message":{"role":"assistant","content":"Готово"}}],"usage":{"prompt_tokens":7,"completion_tokens":3}}"""
                    exchange.responseHeaders.add("Content-Type", "application/json")
                    exchange.sendResponseHeaders(200, response.toByteArray().size.toLong())
                    exchange.responseBody.use { it.write(response.toByteArray()) }
                }
                start()
            }
            try {
                val configFile = Files.createTempFile("harness", ".properties")
                val tokenProperty = "models.deepseek." + "token"
                Files.writeString(
                    configFile,
                    """
                    models.deepseek.endpoint=http://127.0.0.1:${server.address.port}/v1
                    $tokenProperty=test-access-value
                    models.deepseek.models=deepseek-chat
                    """.trimIndent(),
                )
                val config = HarnessConfig.fromFile(configFile)
                val provider = OpenAiCompatibleModelProvider(config.modelConnections)

                val result = provider.complete(
                    "deepseek",
                    ModelCompletionRequest(model = "deepseek-chat", prompt = "Ответь кратко", externalContextApproved = true),
                )

                assertEquals("Готово", result.content)
                assertEquals(7, result.inputTokens)
                assertEquals(3, result.outputTokens)
                assertEquals("Bearer test-access-value", authorization)
                assertEquals("/v1/chat/completions", requestPath)
                assertTrue(config.modelProfiles.first { it.id == "deepseek" }.endpointConfigured)
            } finally {
                server.stop(0)
            }
        }
    }

    @Test
    fun `cloud model rejects context without approval before sending it`() {
        runBlocking {
            val config = HarnessConfig.fromProperties(Properties().apply {
                setProperty("models.deepseek.endpoint", "https://api.deepseek.com/v1")
                setProperty("models.deepseek." + "token", "test-access-value")
                setProperty("models.deepseek.models", "deepseek-chat")
            })

            assertFailsWith<IllegalArgumentException> {
                OpenAiCompatibleModelProvider(config.modelConnections).complete(
                    "deepseek",
                    ModelCompletionRequest(prompt = "Нельзя отправлять без approval"),
                )
            }
        }
    }
}
