package com.harnessadvent.application

import com.harnessadvent.adapters.AllowedProjectPolicy
import com.harnessadvent.adapters.SafeProjectScanner
import com.harnessadvent.domain.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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
    private val codeReviewAutoApprovedContextProfiles: Set<String> = emptySet(),
) {
    suspend fun create(
        projectId: String,
        scenario: TaskScenario,
        mode: TaskMode,
        input: String,
        modelProfileId: String,
        author: String,
        idempotencyKey: String?,
    ): Task = createInternal(
        projectId = projectId,
        scenario = scenario,
        mode = mode,
        input = input,
        modelProfileId = modelProfileId,
        author = author,
        idempotencyKey = idempotencyKey,
        startImmediately = true,
    ).first

    suspend fun createCodeReview(
        projectId: String,
        review: CodeReviewInput,
        modelProfileId: String,
        author: String,
        idempotencyKey: String?,
    ): Task {
        val safeReview = review.sanitizedForStorage()
        validateReviewInput(safeReview)
        if (!idempotencyKey.isNullOrBlank()) repository.findByIdempotencyKey(idempotencyKey)?.let { return it }
        val (task, isNew) = createInternal(
            projectId = projectId,
            scenario = TaskScenario.CODE_REVIEW,
            mode = TaskMode.READ_ONLY,
            input = "Автоматическое ревью PR #${safeReview.pullRequestNumber}: ${safeReview.pullRequestTitle.take(500)}",
            modelProfileId = modelProfileId,
            author = author,
            idempotencyKey = idempotencyKey,
            startImmediately = false,
        )
        if (!isNew) return task
        val time = System.currentTimeMillis()
        repository.addArtifact(Artifact(UUID.randomUUID().toString(), task.id, "codeReviewInput", content = Json.encodeToString(safeReview), createdAt = time))
        repository.addArtifact(
            Artifact(
                UUID.randomUUID().toString(), task.id, "prMetadata",
                content = "repository=${safeReview.repository}\npr=${safeReview.pullRequestNumber}\nheadSha=${safeReview.headSha}\ntitle=${safeReview.pullRequestTitle}",
                createdAt = time,
            ),
        )
        repository.addArtifact(Artifact(UUID.randomUUID().toString(), task.id, "changedFiles", content = Json.encodeToString(safeReview.changedFiles), createdAt = time))
        repository.addArtifact(Artifact(UUID.randomUUID().toString(), task.id, "prDiff", content = safeReview.diff, createdAt = time))
        if (task.status == TaskStatus.QUEUED) executor.start(task.id)
        return task
    }

    private suspend fun createInternal(
        projectId: String,
        scenario: TaskScenario,
        mode: TaskMode,
        input: String,
        modelProfileId: String,
        author: String,
        idempotencyKey: String?,
        startImmediately: Boolean,
    ): Pair<Task, Boolean> {
        require(input.isNotBlank()) { "Описание задания обязательно." }
        require(projectRepository.find(projectId) != null) { "Проект не найден." }
        val profile = modelProfileService.find(modelProfileId)
        require(profile.endpointConfigured) { "Выбранный профиль модели не настроен." }
        if (!idempotencyKey.isNullOrBlank()) repository.findByIdempotencyKey(idempotencyKey)?.let { return it to false }
        val time = System.currentTimeMillis()
        val contextTransferApprovedByPolicy = scenario == TaskScenario.CODE_REVIEW &&
            profile.contextPolicy == ContextPolicy.APPROVED_TASK_CONTEXT &&
            profile.id in codeReviewAutoApprovedContextProfiles
        val pendingApproval = when {
            profile.contextPolicy == ContextPolicy.APPROVED_TASK_CONTEXT && !contextTransferApprovedByPolicy -> ApprovalKind.CONTEXT_TRANSFER
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
        if (contextTransferApprovedByPolicy) {
            repository.addApproval(
                Approval(
                    UUID.randomUUID().toString(), task.id, ApprovalKind.CONTEXT_TRANSFER, ApprovalDecision.APPROVED,
                    "policy:code-review", time,
                ),
            )
            val event = TaskEvent(
                UUID.randomUUID().toString(), task.id, time, EventLevel.INFO, "context.transfer.auto_approved",
                "Передача контекста профилю ${profile.id} автоматически разрешена политикой code review.",
            )
            repository.addEvent(event)
            eventStream.publish(event)
        }
        if (pendingApproval == null && startImmediately) executor.start(task.id)
        return task to true
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

    private fun validateReviewInput(review: CodeReviewInput) {
        require(REPOSITORY_PATTERN.matches(review.repository)) { "Репозиторий должен иметь формат owner/repository." }
        require(review.pullRequestNumber > 0) { "Номер PR должен быть положительным." }
        require(review.pullRequestTitle.isNotBlank() && review.pullRequestTitle.length <= 500) { "Название PR некорректно." }
        require(review.headSha.matches(Regex("[0-9a-fA-F]{7,64}"))) { "Некорректный head SHA." }
        require(review.diff.isNotBlank() && review.diff.length <= MAX_REVIEW_DIFF_SIZE) { "Diff отсутствует или превышает лимит 200000 символов." }
        require(review.changedFiles.isNotEmpty() && review.changedFiles.size <= MAX_CHANGED_FILES) { "Список изменённых файлов отсутствует или превышает лимит 300 файлов." }
        review.changedFiles.forEach { file ->
            require(file.path.isSafeRelativePath()) { "Недопустимый путь изменённого файла." }
            require(file.previousPath == null || file.previousPath.isSafeRelativePath()) { "Недопустимый предыдущий путь файла." }
            require(file.status.length in 1..32) { "Некорректный статус файла." }
        }
    }

    private fun String.isSafeRelativePath(): Boolean =
        isNotBlank() && length <= 1024 && !startsWith('/') && !contains('\\') && split('/').none { it == ".." || it.isBlank() }

    private fun CodeReviewInput.sanitizedForStorage(): CodeReviewInput = copy(
        pullRequestTitle = CodeReviewTextSanitizer.redact(pullRequestTitle),
        diff = CodeReviewTextSanitizer.redact(diff),
    )

    private companion object {
        const val MAX_REVIEW_DIFF_SIZE = 200_000
        const val MAX_CHANGED_FILES = 300
        val REPOSITORY_PATTERN = Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")
    }
}

internal object CodeReviewTextSanitizer {
    private val githubSecretExpression = Regex("\\$\\{\\{\\s*secrets\\.[^}]+}}", RegexOption.IGNORE_CASE)
    private val secretAssignment = Regex("(?i)(api[_-]?key|token|secret|password)\\s*[:=]\\s*\\S+")
    private val privateKeyBlock = Regex("-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----")

    fun redact(text: String): String = text
        .replace(githubSecretExpression, "[redacted GitHub secret reference]")
        .replace(secretAssignment, "sensitive-setting: [redacted]")
        .replace(privateKeyBlock, "[redacted private key]")
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
