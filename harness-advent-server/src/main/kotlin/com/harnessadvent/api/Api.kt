package com.harnessadvent.api

import com.harnessadvent.application.ModelProfileService
import com.harnessadvent.application.HelpCommandService
import com.harnessadvent.application.McpService
import com.harnessadvent.application.ProjectService
import com.harnessadvent.application.TaskService
import com.harnessadvent.application.TestCoverageService
import com.harnessadvent.bootstrap.HarnessConfig
import com.harnessadvent.domain.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.callid.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.cio.ChannelWriteException
import kotlinx.coroutines.flow.filter
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.koin.ktor.ext.inject
import java.security.MessageDigest
import java.util.*

@Serializable
data class ApiError(val code: String, val message: String, val requestId: String)

@Serializable
data class HealthResponse(val status: String = "ok")

@Serializable
data class ProjectCreateRequest(val name: String, val path: String)

@Serializable
data class TaskCreateRequest(
    val projectId: String,
    val scenario: TaskScenario,
    val mode: TaskMode,
    val input: String,
    val modelProfileId: String,
)

@Serializable
data class CodeReviewCreateRequest(
    val projectId: String,
    val modelProfileId: String,
    val repository: String,
    val pullRequestNumber: Int,
    val pullRequestTitle: String,
    val headSha: String,
    val diff: String,
    val changedFiles: List<ChangedFile>,
) {
    fun toDomain() = CodeReviewInput(
        repository = repository,
        pullRequestNumber = pullRequestNumber,
        pullRequestTitle = pullRequestTitle,
        headSha = headSha,
        diff = diff,
        changedFiles = changedFiles,
    )
}

@Serializable
data class ApprovalCreateRequest(val kind: ApprovalKind, val decision: ApprovalDecision)

@Serializable
data class HelpCommandRequest(val projectId: String, val command: String, val modelProfileId: String)

@Serializable
data class SupportAnswerCreateRequest(
    val projectId: String,
    val ticketId: String,
    val question: String,
    val modelProfileId: String,
)

@Serializable
data class TestGenerationCreateRequest(val projectId: String, val modelProfileId: String)

@Serializable
data class McpToolCallRequest(val arguments: JsonObject = JsonObject(emptyMap()))

fun Application.configureMonitoring() {
    install(CallId) {
        header(HttpHeaders.XRequestId)
        generate { UUID.randomUUID().toString() }
        verify { it.length in 1..128 }
        replyToHeader(HttpHeaders.XRequestId)
    }
    install(CallLogging) {
        format { call ->
            val status = call.response.status()?.value ?: 0
            val requestId = call.callId ?: "unknown"
            "HTTP ${call.request.httpMethod.value} ${call.request.path()} -> $status requestId=$requestId"
        }
    }
}

fun Application.configureSerialization() {
    install(ContentNegotiation) {
        json(Json {
            ignoreUnknownKeys = false
            encodeDefaults = true
            explicitNulls = false
        })
    }
}

fun Application.configureApi() {
    val projectService by inject<ProjectService>()
    val taskService by inject<TaskService>()
    val modelProfileService by inject<ModelProfileService>()
    val modelProvider by inject<ModelProvider>()
    val helpCommandService by inject<HelpCommandService>()
    val mcpService by inject<McpService>()
    val testCoverageService by inject<TestCoverageService>()
    val config by inject<HarnessConfig>()
    val json = Json { explicitNulls = false }
    val logger = log

    install(StatusPages) {
        exception<IllegalArgumentException> { call, cause ->
            call.respondError(HttpStatusCode.BadRequest, "validation_error", cause.message ?: "Некорректный запрос.")
        }
        exception<NoSuchElementException> { call, cause ->
            call.respondError(HttpStatusCode.NotFound, "not_found", cause.message ?: "Ресурс не найден.")
        }
        exception<ModelProviderException> { call, cause ->
            call.respondError(HttpStatusCode.BadGateway, "model_unavailable", cause.message ?: "Модель временно недоступна.")
        }
        exception<ChannelWriteException> { call, cause ->
            logger.debug("Клиент отключился во время записи ответа requestId=${call.callId}", cause)
        }
        exception<Throwable> { call, cause ->
            logger.error("Необработанная ошибка requestId=${call.callId}", cause)
            if (!call.response.isCommitted) {
                call.respondError(HttpStatusCode.InternalServerError, "internal_error", "Внутренняя ошибка сервера.")
            }
        }
    }

    routing {
        get("/health") { call.respond(HealthResponse()) }

        route("/api/v1") {
            route("/assistant") {
                post("/commands") {
                    val request = call.receive<HelpCommandRequest>()
                    val task = helpCommandService.execute(
                        projectId = request.projectId,
                        command = request.command,
                        modelProfileId = request.modelProfileId,
                        author = call.actor(),
                        idempotencyKey = call.request.headers["Idempotency-Key"],
                    )
                    call.respond(HttpStatusCode.Accepted, task)
                }
            }

            post("/support/answers") {
                val request = call.receive<SupportAnswerCreateRequest>()
                val task = taskService.createSupportAnswer(
                    projectId = request.projectId,
                    ticketId = request.ticketId,
                    question = request.question,
                    modelProfileId = request.modelProfileId,
                    author = call.actor(),
                    idempotencyKey = call.request.headers["Idempotency-Key"],
                )
                call.respond(HttpStatusCode.Accepted, task)
            }

            route("/test-generation") {
                post {
                    val request = call.receive<TestGenerationCreateRequest>()
                    val result = testCoverageService.start(
                        projectId = request.projectId,
                        modelProfileId = request.modelProfileId,
                        author = call.actor(),
                        idempotencyKey = call.request.headers["Idempotency-Key"],
                    )
                    call.respond(HttpStatusCode.Accepted, result)
                }
            }

            route("/mcp/servers") {
                get { call.respond(mcpService.servers()) }
                get("/{id}/tools") { call.respond(mcpService.tools(call.requiredId())) }
                post("/{id}/tools/{toolName}") {
                    val serverId = call.requiredId()
                    val toolName = requireNotNull(call.parameters["toolName"]?.takeIf { it.isNotBlank() }) { "Имя MCP-инструмента обязательно." }
                    val request = call.receive<McpToolCallRequest>()
                    call.respond(mcpService.call(serverId, toolName, request.arguments))
                }
            }

            route("/projects") {
                get { call.respond(projectService.list()) }
                post {
                    val request = call.receive<ProjectCreateRequest>()
                    call.respond(HttpStatusCode.Created, projectService.register(request.name, request.path))
                }
                get("/{id}") { call.respond(projectService.get(call.requiredId())) }
                post("/{id}/scan") { call.respond(projectService.scan(call.requiredId())) }
                get("/{id}/test-coverage-plan") { call.respond(testCoverageService.get(call.requiredId())) }
            }

            post("/code-reviews") {
                val token = config.codeReviewApiToken
                if (token.isNullOrBlank()) {
                    call.respondError(HttpStatusCode.ServiceUnavailable, "code_review_disabled", "Приём CI-ревью не настроен.")
                    return@post
                }
                if (!call.hasBearerToken(token)) {
                    call.respondError(HttpStatusCode.Unauthorized, "unauthorized", "Недействительный токен CI-ревью.")
                    return@post
                }
                val request = call.receive<CodeReviewCreateRequest>()
                val task = taskService.createCodeReview(
                    projectId = request.projectId,
                    review = request.toDomain(),
                    modelProfileId = request.modelProfileId,
                    author = "github-actions",
                    idempotencyKey = call.request.headers["Idempotency-Key"],
                )
                call.respond(HttpStatusCode.Accepted, task)
            }

            route("/tasks") {
                get { call.respond(taskService.list()) }
                post {
                    val request = call.receive<TaskCreateRequest>()
                    val task = taskService.create(
                        projectId = request.projectId,
                        scenario = request.scenario,
                        mode = request.mode,
                        input = request.input,
                        modelProfileId = request.modelProfileId,
                        author = call.actor(),
                        idempotencyKey = call.request.headers["Idempotency-Key"],
                    )
                    call.respond(HttpStatusCode.Accepted, task)
                }
                get("/{id}") { call.respond(taskService.get(call.requiredId())) }
                post("/{id}/cancel") { call.respond(taskService.cancel(call.requiredId())) }
                get("/{id}/artifacts") { call.respond(taskService.artifacts(call.requiredId())) }
                post("/{id}/approvals") {
                    val request = call.receive<ApprovalCreateRequest>()
                    call.respond(taskService.approve(call.requiredId(), request.kind, request.decision, call.actor()))
                }
                get("/{id}/events") {
                    val taskId = call.requiredId()
                    taskService.get(taskId)
                    call.response.cacheControl(CacheControl.NoCache(null))
                    call.respondTextWriter(contentType = ContentType.Text.EventStream) {
                        taskService.events(taskId).forEach { event ->
                            write("data: ${json.encodeToString(TaskEvent.serializer(), event)}\n\n")
                        }
                        flush()
                        taskService.updates().filter { it.taskId == taskId }.collect { event ->
                            write("data: ${json.encodeToString(TaskEvent.serializer(), event)}\n\n")
                            flush()
                        }
                    }
                }
            }

            route("/model-profiles") {
                get { call.respond(modelProfileService.list()) }
                get("/{id}/models") { call.respond(modelProfileService.find(call.requiredId()).models) }
                get("/{id}/health") {
                    val profileId = call.requiredId()
                    modelProfileService.find(profileId)
                    call.respond(modelProvider.health(profileId))
                }
                post("/{id}/completions") {
                    val profileId = call.requiredId()
                    modelProfileService.find(profileId)
                    call.respond(modelProvider.complete(profileId, call.receive<ModelCompletionRequest>()))
                }
            }
        }
    }
}

private suspend fun ApplicationCall.respondError(status: HttpStatusCode, code: String, message: String) {
    respond(status, ApiError(code, message, callId ?: "unknown"))
}

private fun ApplicationCall.requiredId(): String =
    requireNotNull(parameters["id"]?.takeIf { it.isNotBlank() }) { "Идентификатор обязателен." }

private fun ApplicationCall.actor(): String =
    request.headers["X-Actor"]?.trim()?.takeIf { it.length in 1..128 } ?: "local"

private fun ApplicationCall.hasBearerToken(expected: String): Boolean {
    val provided = request.headers[HttpHeaders.Authorization]
        ?.takeIf { it.startsWith("Bearer ") }
        ?.removePrefix("Bearer ")
        ?.takeIf { it.length == expected.length }
        ?: return false
    return MessageDigest.isEqual(provided.toByteArray(), expected.toByteArray())
}
