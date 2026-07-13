package com.harnessadvent.application

import com.harnessadvent.adapters.AllowedProjectPolicy
import com.harnessadvent.adapters.SafeProjectScanner
import com.harnessadvent.domain.*
import kotlinx.serialization.json.JsonObject
import java.util.UUID

class ProjectService(
    private val repository: ProjectRepository,
    private val sourceRepository: SourceRepository,
    private val allowedProjectPolicy: AllowedProjectPolicy,
    private val scanner: SafeProjectScanner,
) {
    suspend fun register(name: String, path: String): Project {
        require(name.isNotBlank()) { "Имя проекта обязательно." }
        val allowedPath = allowedProjectPolicy.validate(path)
        return repository.upsert(
            Project(UUID.randomUUID().toString(), name.trim(), allowedPath.toString(), ProjectScanStatus.NOT_SCANNED),
        )
    }

    suspend fun list(): List<Project> = repository.list()
    suspend fun get(id: String): Project = requireNotNull(repository.find(id)) { "Проект не найден." }

    suspend fun scan(id: String): Project {
        val project = get(id)
        val documents = scanner.scan(project.id, allowedProjectPolicy.validate(project.path))
        sourceRepository.replaceForProject(project.id, documents)
        return repository.upsert(project.copy(scanStatus = ProjectScanStatus.READY, lastScannedAt = System.currentTimeMillis()))
    }
}

class TaskService(
    private val projectRepository: ProjectRepository,
    private val repository: TaskRepository,
    private val executor: TaskExecutor,
    private val eventStream: TaskEventStream,
    private val modelProfileService: ModelProfileService,
) {
    suspend fun create(
        projectId: String,
        scenario: TaskScenario,
        mode: TaskMode,
        input: String,
        modelProfileId: String,
        author: String,
        idempotencyKey: String?,
    ): Task {
        require(input.isNotBlank()) { "Описание задания обязательно." }
        require(projectRepository.find(projectId) != null) { "Проект не найден." }
        val profile = modelProfileService.find(modelProfileId)
        require(profile.endpointConfigured) { "Выбранный профиль модели не настроен." }
        if (!idempotencyKey.isNullOrBlank()) repository.findByIdempotencyKey(idempotencyKey)?.let { return it }
        val time = System.currentTimeMillis()
        val pendingApproval = when {
            profile.contextPolicy == ContextPolicy.APPROVED_TASK_CONTEXT -> ApprovalKind.CONTEXT_TRANSFER
            mode == TaskMode.MAY_MODIFY -> ApprovalKind.FILE_MODIFICATION
            else -> null
        }
        val task = repository.create(
            Task(
                UUID.randomUUID().toString(), projectId, scenario, mode,
                if (pendingApproval == null) TaskStatus.QUEUED else TaskStatus.WAITING_APPROVAL,
                author, input.trim(), time, time, modelProfileId, pendingApproval,
            ),
            idempotencyKey,
        )
        if (pendingApproval == null) executor.start(task.id)
        return task
    }

    suspend fun get(id: String): Task = requireNotNull(repository.find(id)) { "Задание не найдено." }
    suspend fun list(): List<Task> = repository.list()
    suspend fun events(id: String): List<TaskEvent> = repository.events(get(id).id)
    suspend fun artifacts(id: String): List<Artifact> = repository.artifacts(get(id).id)
    fun updates() = eventStream.updates()

    suspend fun cancel(id: String): Task {
        get(id)
        executor.cancel(id)
        return get(id)
    }

    suspend fun approve(id: String, kind: ApprovalKind, decision: ApprovalDecision, author: String): Approval {
        val task = get(id)
        require(task.status == TaskStatus.WAITING_APPROVAL) { "Задание не ожидает подтверждения." }
        require(task.pendingApprovalKind == kind) { "Запрошен другой тип подтверждения." }
        val approval = Approval(UUID.randomUUID().toString(), id, kind, decision, author, System.currentTimeMillis())
        repository.addApproval(approval)
        if (decision == ApprovalDecision.APPROVED) {
            val nextApproval = if (kind == ApprovalKind.CONTEXT_TRANSFER && task.mode == TaskMode.MAY_MODIFY) {
                ApprovalKind.FILE_MODIFICATION
            } else {
                null
            }
            if (nextApproval != null) {
                repository.updatePendingApproval(id, nextApproval, System.currentTimeMillis())
            } else {
                repository.updatePendingApproval(id, null, System.currentTimeMillis())
                repository.updateStatus(id, TaskStatus.QUEUED, System.currentTimeMillis())
                executor.resumeAfterApproval(id)
            }
        } else {
            executor.cancel(id)
        }
        return approval
    }
}

class ModelProfileService(private val profiles: List<ModelProfile>) {
    fun list(): List<ModelProfile> = profiles
    fun find(id: String): ModelProfile = requireNotNull(profiles.find { it.id == id }) { "Профиль модели не найден." }
}

class HelpCommandService(private val taskService: TaskService) {
    suspend fun execute(projectId: String, command: String, modelProfileId: String, author: String, idempotencyKey: String?): Task {
        val question = command.trim().removePrefix("/help").trim()
        require(command.trim().startsWith("/help")) { "Поддерживается только команда /help." }
        return taskService.create(
            projectId = projectId,
            scenario = TaskScenario.RAG_QUESTION,
            mode = TaskMode.READ_ONLY,
            input = question.ifBlank { "Как пользоваться этим проектом?" },
            modelProfileId = modelProfileId,
            author = author,
            idempotencyKey = idempotencyKey,
        )
    }
}

class McpService(private val registry: McpRegistry) {
    suspend fun servers(): List<McpServer> = registry.servers()
    suspend fun tools(serverId: String): List<McpTool> = registry.tools(serverId)
    suspend fun call(serverId: String, toolName: String, arguments: JsonObject): McpToolResult =
        registry.call(serverId, toolName, arguments)
}
