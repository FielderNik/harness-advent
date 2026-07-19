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
import com.harnessadvent.domain.McpRegistry
import com.harnessadvent.domain.McpServer
import com.harnessadvent.domain.McpTool
import com.harnessadvent.domain.McpToolResult
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonObject
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
        assertContains(artifacts, "Example.kt")
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
    fun `support answer combines a YouTrack ticket with Trainingdiary RAG sources`() = testApplication {
        val repository = Files.createTempDirectory("trainingdiary")
        Files.writeString(
            repository.resolve("README.md"),
            "# Trainingdiary\n\n## Авторизация\nПри ошибке входа пользователь должен повторить вход после очистки локальной сессии.",
        )
        var prompt = ""
        val supportModel = object : ModelProvider {
            override suspend fun health(profileId: String) = ModelProviderHealth(profileId, true, "test")

            override suspend fun complete(profileId: String, request: ModelCompletionRequest): ModelCompletionResult {
                prompt = request.prompt
                return ModelCompletionResult(profileId, "test-model", "Повторите вход после очистки сессии. [README.md:3]")
            }
        }
        val youTrackMcp = object : McpRegistry {
            override suspend fun servers() = listOf(McpServer("youtrack", "YouTrack", true, true, emptySet()))
            override suspend fun tools(serverId: String) = listOf(McpTool("youtrack_get_issue"))
            override suspend fun call(serverId: String, toolName: String, arguments: JsonObject): McpToolResult {
                assertEquals("youtrack", serverId)
                assertEquals("youtrack_get_issue", toolName)
                assertEquals("TRAIN-42", arguments["issueId"]?.jsonPrimitive?.content)
                return McpToolResult(
                    content = """[{"type":"text","text":"{\"readableId\":\"TRAIN-42\",\"summary\":\"Не работает авторизация\",\"description\":\"После смены пароля вход завершается ошибкой. Контакт contact@example.test\",\"resolved\":null,\"project\":{\"shortName\":\"TRAIN\"},\"customFields\":[{\"name\":\"State\",\"value\":{\"name\":\"Open\"}}]}"}]""",
                )
            }
        }
        application { moduleForTests(testConfig(repository), supportModel, youTrackMcp) }
        val projectId = createProject(client, repository)
        client.post("/api/v1/projects/$projectId/scan")

        val response = client.post("/api/v1/support/answers") {
            contentType(ContentType.Application.Json)
            setBody("""{"projectId":"$projectId","ticketId":"TRAIN-42","question":"Почему не работает авторизация?","modelProfileId":"local"}""")
        }

        assertEquals(HttpStatusCode.Accepted, response.status)
        val taskId = Json.parseToJsonElement(response.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
        eventually {
            val task = client.get("/api/v1/tasks/$taskId").bodyAsText()
            task.contains("completed") || task.contains("failed")
        }
        assertContains(client.get("/api/v1/tasks/$taskId").bodyAsText(), "completed")
        val artifacts = client.get("/api/v1/tasks/$taskId/artifacts").bodyAsText()
        assertContains(artifacts, "supportTicketContext")
        assertContains(artifacts, "supportSources")
        assertContains(artifacts, "supportAnswer")
        assertFalse(artifacts.contains("contact@example.test"))
        assertContains(prompt, "[redacted email]")
        assertContains(prompt, "README.md:1-4")
        assertContains(prompt, "[тикет: TRAIN-42]")
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

    @Test
    fun `CI code review receives diff files and RAG context`() = testApplication {
        val repository = Files.createTempDirectory("harness-project")
        Files.writeString(repository.resolve("README.md"), "# Fixture\n\nDo not return null from public APIs.")
        Files.writeString(repository.resolve("Example.kt"), "package test\nclass Example { fun answer() = 42 }")
        var reviewPrompt = ""
        val reviewModelProvider = object : ModelProvider {
            override suspend fun health(profileId: String) = ModelProviderHealth(profileId, true, "test")

            override suspend fun complete(profileId: String, request: ModelCompletionRequest): ModelCompletionResult {
                reviewPrompt = request.prompt
                return ModelCompletionResult(
                    profileId, "test-model",
                    """{"summary":"## Потенциальные баги\nНайдено замечание.\n\n## Архитектурные проблемы\nНет.\n\n## Рекомендации\nНет.","comments":[{"path":"Example.kt","line":1,"severity":"high","body":"Возвращается null."},{"path":"Example.kt","line":99,"severity":"low","body":"apiKey=must-not-leak"}]}""",
                )
            }
        }
        application { moduleForTests(testConfig(repository), reviewModelProvider) }
        val projectId = createProject(client, repository)
        client.post("/api/v1/projects/$projectId/scan")

        val response = client.post("/api/v1/code-reviews") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer test-review-token")
            header("Idempotency-Key", "pr-7-deadbeef")
            setBody(
                """{"projectId":"$projectId","modelProfileId":"local","repository":"owner/repository","pullRequestNumber":7,"pullRequestTitle":"Fix answer","headSha":"deadbeefdeadbeefdeadbeefdeadbeefdeadbeef","diff":"diff --git a/Example.kt b/Example.kt\n@@ -1,2 +1,2 @@\n-class Example { fun answer() = 42 }\n+class Example { fun answer(): Int? = null }","changedFiles":[{"path":"Example.kt","status":"M"}]}""",
            )
        }

        assertEquals(HttpStatusCode.Accepted, response.status)
        val taskId = Json.parseToJsonElement(response.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
        eventually { client.get("/api/v1/tasks/$taskId").bodyAsText().contains("completed") }
        val artifacts = client.get("/api/v1/tasks/$taskId/artifacts").bodyAsText()
        assertContains(artifacts, "prDiff")
        assertContains(artifacts, "changedFiles")
        assertContains(artifacts, "reviewSources")
        assertContains(artifacts, "Example.kt")
        assertContains(artifacts, "codeReviewReport")
        assertContains(artifacts, "githubReview")
        val githubReviewContent = Json.parseToJsonElement(artifacts).jsonArray
            .first { it.jsonObject["type"]?.jsonPrimitive?.content == "githubReview" }
            .jsonObject["content"]!!.jsonPrimitive.content
        val githubReview = Json.parseToJsonElement(githubReviewContent).jsonObject
        assertEquals(1, assertNotNull(githubReview["comments"], githubReviewContent).jsonArray.size)
        assertFalse(githubReviewContent.contains("must-not-leak"))
        assertContains(githubReview["summary"]!!.jsonPrimitive.content, "Замечания без привязки к строке")
        assertContains(reviewPrompt, "JSON-объект")
        assertContains(reviewPrompt, "Example.kt")
        assertContains(reviewPrompt, "Релевантный контекст RAG")
    }

    @Test
    fun `CI code review rejects a missing token`() = testApplication {
        application { moduleForTests(testConfig(), testModelProvider) }

        val response = client.post("/api/v1/code-reviews") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `CI code review redacts GitHub secret references before storing and sending context`() = testApplication {
        val repository = Files.createTempDirectory("harness-project")
        application { moduleForTests(testConfig(repository), testModelProvider) }
        val projectId = createProject(client, repository)
        val githubSecretReference = "${'$'}{{ secrets.HARNESS_CODE_REVIEW_TOKEN }}"

        val response = client.post("/api/v1/code-reviews") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer test-review-token")
            setBody(
                """{"projectId":"$projectId","modelProfileId":"local","repository":"owner/repository","pullRequestNumber":9,"pullRequestTitle":"Workflow","headSha":"deadbeefdeadbeefdeadbeefdeadbeefdeadbeef","diff":"diff --git a/workflow.yml b/workflow.yml\n+ HARNESS_CODE_REVIEW_TOKEN: $githubSecretReference","changedFiles":[{"path":"workflow.yml","status":"M"}]}""",
            )
        }

        val taskId = Json.parseToJsonElement(response.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
        eventually { client.get("/api/v1/tasks/$taskId").bodyAsText().contains("completed") }
        val artifacts = client.get("/api/v1/tasks/$taskId/artifacts").bodyAsText()
        assertFalse(artifacts.contains("HARNESS_CODE_REVIEW_TOKEN"))
        assertContains(artifacts, "sensitive-setting")
    }

    @Test
    fun `configured DeepSeek code review skips manual context approval`() = testApplication {
        val repository = Files.createTempDirectory("harness-project")
        val config = testConfig(repository).copy(codeReviewAutoApprovedContextProfiles = setOf("deepseek"))
        application { moduleForTests(config, testModelProvider) }
        val projectId = createProject(client, repository)

        val response = client.post("/api/v1/code-reviews") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer test-review-token")
            setBody(
                """{"projectId":"$projectId","modelProfileId":"deepseek","repository":"owner/repository","pullRequestNumber":8,"pullRequestTitle":"Cloud review","headSha":"deadbeefdeadbeefdeadbeefdeadbeefdeadbeef","diff":"diff --git a/Example.kt b/Example.kt\n@@ -1 +1 @@\n+class Example","changedFiles":[{"path":"Example.kt","status":"A"}]}""",
            )
        }

        assertEquals(HttpStatusCode.Accepted, response.status)
        val taskBody = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertNotEquals("waitingApproval", taskBody["status"]?.jsonPrimitive?.content)
        assertFalse(taskBody.containsKey("pendingApprovalKind"))
        val taskId = taskBody["id"]!!.jsonPrimitive.content
        eventually { client.get("/api/v1/tasks/$taskId").bodyAsText().contains("completed") }
    }

    @Test
    fun `file assistant searches reads and writes only the registered project`() = testApplication {
        val repository = Files.createTempDirectory("harness-project")
        Files.writeString(repository.resolve("README.md"), "# Fixture\n\nThe answer is 42.")
        Files.writeString(repository.resolve("Example.kt"), "package test\nfun answer() = 42")
        Files.createDirectories(repository.resolve("docs"))
        val actions = ArrayDeque(
            listOf(
                """{"action":"search","query":"answer"}""",
                """{"action":"read","path":"README.md"}""",
                "{\"action\":\"write\",\"path\":\"docs/CHANGELOG.md\",\"content\":\"# Changelog\\n\\n- Documented answer.\"}",
                """{"action":"finish","summary":"Документация обновлена."}""",
            ),
        )
        val fileModelProvider = object : ModelProvider {
            override suspend fun health(profileId: String) = ModelProviderHealth(profileId, true, "test")
            override suspend fun complete(profileId: String, request: ModelCompletionRequest) = ModelCompletionResult(
                profileId, "test-model", actions.removeFirst(),
            )
        }
        application { moduleForTests(testConfig(repository), fileModelProvider) }
        val projectId = createProject(client, repository)

        val task = client.post("/api/v1/tasks") {
            contentType(ContentType.Application.Json)
            setBody("""{"projectId":"$projectId","scenario":"agentWorkflow","mode":"mayModify","modelProfileId":"local","input":"Обнови changelog по ответу"}""")
        }
        val taskId = Json.parseToJsonElement(task.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
        assertEquals(HttpStatusCode.OK, client.post("/api/v1/tasks/$taskId/approvals") {
            contentType(ContentType.Application.Json)
            setBody("""{"kind":"fileModification","decision":"approved"}""")
        }.status)

        eventually { client.get("/api/v1/tasks/$taskId").bodyAsText().contains("completed") }
        assertEquals("# Changelog\n\n- Documented answer.", Files.readString(repository.resolve("docs/CHANGELOG.md")))
        val artifacts = client.get("/api/v1/tasks/$taskId/artifacts").bodyAsText()
        assertContains(artifacts, "fileInventory")
        assertContains(artifacts, "fileOperations")
        assertContains(artifacts, "fileChanges")
        assertContains(artifacts, "docs/CHANGELOG.md")
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
        codeReviewApiToken = "test-review-token",
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
