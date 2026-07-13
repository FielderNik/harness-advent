package com.harnessadvent

import com.harnessadvent.bootstrap.HarnessConfig
import com.harnessadvent.bootstrap.McpServerConnection
import com.harnessadvent.bootstrap.moduleForTests
import com.harnessadvent.domain.ContextPolicy
import com.harnessadvent.domain.ModelCompletionRequest
import com.harnessadvent.domain.ModelCompletionResult
import com.harnessadvent.domain.ModelProfile
import com.harnessadvent.domain.ModelProvider
import com.harnessadvent.domain.ModelProviderHealth
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.test.*

class ServerTest {
    @Test
    fun `health endpoint is available without secrets`() = testApplication {
        application { moduleForTests(testConfig(), testModelProvider) }

        val response = client.get("/health")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("ok", Json.parseToJsonElement(response.bodyAsText()).jsonObject["status"]?.jsonPrimitive?.content)
    }

    @Test
    fun `registered project can be scanned and searched by read only task`() = testApplication {
        val repository = Files.createTempDirectory("harness-project")
        Files.writeString(repository.resolve("README.md"), "# Fixture\n\n## Answer\nThe answer is 42.")
        Files.writeString(repository.resolve("Example.kt"), "package test\nclass Example { fun answer() = 42 }")
        application { moduleForTests(testConfig(repository), testModelProvider) }

        val project = client.post("/api/v1/projects") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Fixture","path":"${repository}"}""")
        }
        assertEquals(HttpStatusCode.Created, project.status)
        val projectId = Json.parseToJsonElement(project.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
        assertEquals(HttpStatusCode.OK, client.post("/api/v1/projects/$projectId/scan").status)

        val task = client.post("/api/v1/tasks") {
            contentType(ContentType.Application.Json)
            header("Idempotency-Key", "search-example")
            setBody("""{"projectId":"$projectId","scenario":"ragQuestion","mode":"readOnly","modelProfileId":"local","input":"Answer"}""")
        }
        assertEquals(HttpStatusCode.Accepted, task.status)
        val taskId = Json.parseToJsonElement(task.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        eventually {
            val result = client.get("/api/v1/tasks/$taskId")
            Json.parseToJsonElement(result.bodyAsText()).jsonObject["status"]?.jsonPrimitive?.content == "completed"
        }
        val artifacts = client.get("/api/v1/tasks/$taskId/artifacts").bodyAsText()
        assertContains(artifacts, "README.md")
        assertFalse(artifacts.contains("Example.kt"))
    }

    @Test
    fun `help command creates a read only RAG task`() = testApplication {
        val repository = Files.createTempDirectory("harness-project")
        Files.writeString(repository.resolve("README.md"), "# Fixture\n\n## Structure\nServer contains API and RAG.")
        application { moduleForTests(testConfig(repository), testModelProvider) }
        val projectId = createProject(client, repository)
        client.post("/api/v1/projects/$projectId/scan")

        val response = client.post("/api/v1/assistant/commands") {
            contentType(ContentType.Application.Json)
            setBody("""{"projectId":"$projectId","command":"/help Как устроен сервер?","modelProfileId":"local"}""")
        }

        assertEquals(HttpStatusCode.Accepted, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("ragQuestion", body["scenario"]?.jsonPrimitive?.content)
        assertEquals("readOnly", body["mode"]?.jsonPrimitive?.content)
        assertEquals("Как устроен сервер?", body["input"]?.jsonPrimitive?.content)
    }

    @Test
    fun `MCP server list keeps token out of API`() = testApplication {
        val repository = Files.createTempDirectory("harness-project")
        val config = testConfig(repository).copy(mcpServers = listOf(
            McpServerConnection(
                id = "github", name = "GitHub", command = "docker", arguments = listOf("run"),
                environment = mapOf("GITHUB_PERSONAL_ACCESS_TOKEN" to "must-not-leak", "GITHUB_READ_ONLY" to "1"),
                readOnly = true, allowedRepositories = setOf("owner/repository"), allowedTools = setOf("get_file_contents"), timeoutSeconds = 30,
            ),
        ))
        application { moduleForTests(config, testModelProvider) }

        val response = client.get("/api/v1/mcp/servers")

        assertEquals(HttpStatusCode.OK, response.status)
        assertContains(response.bodyAsText(), "owner/repository")
        assertFalse(response.bodyAsText().contains("must-not-leak"))
    }

    @Test
    fun `may modify task waits for an explicit approval`() = testApplication {
        val repository = Files.createTempDirectory("harness-project")
        application { moduleForTests(testConfig(repository), testModelProvider) }
        val projectId = createProject(client, repository)

        val task = client.post("/api/v1/tasks") {
            contentType(ContentType.Application.Json)
            setBody("""{"projectId":"$projectId","scenario":"agentWorkflow","mode":"mayModify","modelProfileId":"local","input":"Change code"}""")
        }
        val taskId = Json.parseToJsonElement(task.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        eventually {
            client.get("/api/v1/tasks/$taskId").bodyAsText().contains("waitingApproval")
        }
        val approval = client.post("/api/v1/tasks/$taskId/approvals") {
            contentType(ContentType.Application.Json)
            setBody("""{"kind":"fileModification","decision":"approved"}""")
        }
        assertEquals(HttpStatusCode.OK, approval.status)
        eventually { client.get("/api/v1/tasks/$taskId").bodyAsText().contains("completed") }
    }

    @Test
    fun `cloud model task waits for context transfer approval`() = testApplication {
        val repository = Files.createTempDirectory("harness-project")
        application { moduleForTests(testConfig(repository), testModelProvider) }
        val projectId = createProject(client, repository)

        val task = client.post("/api/v1/tasks") {
            contentType(ContentType.Application.Json)
            setBody("""{"projectId":"$projectId","scenario":"ragQuestion","mode":"readOnly","modelProfileId":"deepseek","input":"Проверь проект"}""")
        }
        val taskId = Json.parseToJsonElement(task.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        eventually {
            client.get("/api/v1/tasks/$taskId").bodyAsText().contains("contextTransfer")
        }
        assertEquals(HttpStatusCode.OK, client.post("/api/v1/tasks/$taskId/approvals") {
            contentType(ContentType.Application.Json)
            setBody("""{"kind":"contextTransfer","decision":"approved"}""")
        }.status)
        eventually { client.get("/api/v1/tasks/$taskId").bodyAsText().contains("completed") }
    }

    private suspend fun createProject(client: io.ktor.client.HttpClient, repository: java.nio.file.Path): String {
        val response = client.post("/api/v1/projects") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Fixture","path":"$repository"}""")
        }
        return Json.parseToJsonElement(response.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
    }

    private fun testConfig(repository: java.nio.file.Path = Files.createTempDirectory("harness-project")): HarnessConfig = HarnessConfig(
        databaseUrl = "jdbc:sqlite:${Files.createTempFile("harness", ".db")}",
        allowedProjectPaths = setOf(repository.toAbsolutePath().normalize()),
        modelProfiles = listOf(
            ModelProfile("local", "test", true, emptyList(), ContextPolicy.LOCAL_ONLY, emptySet()),
            ModelProfile("deepseek", "test", true, emptyList(), ContextPolicy.APPROVED_TASK_CONTEXT, emptySet()),
        ),
    )

    private suspend fun eventually(predicate: suspend () -> Boolean) {
        repeat(100) {
            if (predicate()) return
            kotlinx.coroutines.delay(20)
        }
        fail("Ожидаемое состояние не наступило.")
    }

    private val testModelProvider = object : ModelProvider {
        override suspend fun health(profileId: String) = ModelProviderHealth(profileId, true, "test")

        override suspend fun complete(profileId: String, request: ModelCompletionRequest) = ModelCompletionResult(
            profileId = profileId,
            model = "test-model",
            content = "Ответ модели для: ${request.prompt.take(40)}",
        )
    }
}
