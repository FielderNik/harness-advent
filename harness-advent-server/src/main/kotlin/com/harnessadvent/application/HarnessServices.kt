package com.harnessadvent.application

import com.harnessadvent.adapters.AllowedProjectPolicy
import com.harnessadvent.adapters.SafeProjectScanner
import com.harnessadvent.domain.*
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
) {
    suspend fun create(
        projectId: String,
        scenario: TaskScenario,
        mode: TaskMode,
        input: String,
        author: String,
        idempotencyKey: String?,
    ): Task {
        require(input.isNotBlank()) { "Описание задания обязательно." }
        require(projectRepository.find(projectId) != null) { "Проект не найден." }
        if (!idempotencyKey.isNullOrBlank()) repository.findByIdempotencyKey(idempotencyKey)?.let { return it }
        val time = System.currentTimeMillis()
        val task = repository.create(
            Task(UUID.randomUUID().toString(), projectId, scenario, mode, TaskStatus.QUEUED, author, input.trim(), time, time),
            idempotencyKey,
        )
        executor.start(task.id)
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
        val approval = Approval(UUID.randomUUID().toString(), id, kind, decision, author, System.currentTimeMillis())
        repository.addApproval(approval)
        if (decision == ApprovalDecision.APPROVED) {
            repository.updateStatus(id, TaskStatus.QUEUED, System.currentTimeMillis())
            executor.resumeAfterApproval(id)
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
