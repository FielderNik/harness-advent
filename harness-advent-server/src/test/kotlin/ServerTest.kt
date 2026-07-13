package com.harnessadvent

import com.harnessadvent.bootstrap.HarnessConfig
import com.harnessadvent.bootstrap.module
import com.harnessadvent.domain.ContextPolicy
import com.harnessadvent.domain.ModelProfile
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
        application { module(testConfig()) }

        val response = client.get("/health")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("ok", Json.parseToJsonElement(response.bodyAsText()).jsonObject["status"]?.jsonPrimitive?.content)
    }

    @Test
    fun `registered project can be scanned and searched by read only task`() = testApplication {
        val repository = Files.createTempDirectory("harness-project")
        Files.writeString(repository.resolve("Example.kt"), "package test\nclass Example { fun answer() = 42 }")
        application { module(testConfig(repository)) }

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
            setBody("""{"projectId":"$projectId","scenario":"ragQuestion","mode":"readOnly","input":"Example answer"}""")
        }
        assertEquals(HttpStatusCode.Accepted, task.status)
        val taskId = Json.parseToJsonElement(task.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        eventually {
            val result = client.get("/api/v1/tasks/$taskId")
            Json.parseToJsonElement(result.bodyAsText()).jsonObject["status"]?.jsonPrimitive?.content == "completed"
        }
        val artifacts = client.get("/api/v1/tasks/$taskId/artifacts").bodyAsText()
        assertContains(artifacts, "Example.kt")
    }

    @Test
    fun `may modify task waits for an explicit approval`() = testApplication {
        val repository = Files.createTempDirectory("harness-project")
        application { module(testConfig(repository)) }
        val projectId = createProject(client, repository)

        val task = client.post("/api/v1/tasks") {
            contentType(ContentType.Application.Json)
            setBody("""{"projectId":"$projectId","scenario":"agentWorkflow","mode":"mayModify","input":"Change code"}""")
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
        modelProfiles = listOf(ModelProfile("local", "test", false, emptyList(), ContextPolicy.LOCAL_ONLY, emptySet())),
    )

    private suspend fun eventually(predicate: suspend () -> Boolean) {
        repeat(100) {
            if (predicate()) return
            kotlinx.coroutines.delay(20)
        }
        fail("Ожидаемое состояние не наступило.")
    }
}
