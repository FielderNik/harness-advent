package com.harnessadvent.application

import com.harnessadvent.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class TaskEventStream {
    private val updates = MutableSharedFlow<TaskEvent>(extraBufferCapacity = 128)
    fun updates(): SharedFlow<TaskEvent> = updates.asSharedFlow()
    fun publish(event: TaskEvent) = updates.tryEmit(event)
}

class TaskExecutor(
    private val taskRepository: TaskRepository,
    private val sourceRepository: SourceRepository,
    private val modelProvider: ModelProvider,
    private val events: TaskEventStream,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val jobs = ConcurrentHashMap<String, Job>()

    fun start(taskId: String) {
        if (jobs[taskId]?.isActive == true) return
        jobs[taskId] = scope.launch {
            runCatching { execute(taskId) }
                .onFailure { error ->
                    if (error !is CancellationException) fail(taskId, error.message ?: "Неизвестная ошибка исполнения")
                }
        }
    }

    suspend fun cancel(taskId: String) {
        jobs.remove(taskId)?.cancel()
        val task = taskRepository.find(taskId) ?: return
        if (task.status !in TERMINAL_STATUSES) {
            taskRepository.updateStatus(taskId, TaskStatus.CANCELLED, now())
            publish(taskId, EventLevel.INFO, "task.cancelled", "Задание отменено пользователем.")
        }
    }

    private suspend fun execute(taskId: String) {
        var task = requireNotNull(taskRepository.find(taskId))
        if (task.status in TERMINAL_STATUSES || task.status == TaskStatus.WAITING_APPROVAL) return
        task = taskRepository.updateStatus(taskId, TaskStatus.RUNNING, now())
        publish(taskId, EventLevel.INFO, "task.running", "Фоновый исполнитель начал задание.")

        val shouldComplete = if (task.scenario == TaskScenario.RAG_QUESTION) {
            runRagQuestion(task)
            true
        } else {
            runAgentWorkflow(task)
        }
        if (!shouldComplete) return
        currentCoroutineContext().ensureActive()
        taskRepository.updateStatus(taskId, TaskStatus.COMPLETED, now())
        publish(taskId, EventLevel.INFO, "task.completed", "Задание завершено.")
    }

    private suspend fun runRagQuestion(task: Task) {
        addStep(task, "research", "local-rag", "Поиск локальных источников.")
        val sources = sourceRepository.search(task.projectId, task.input, limit = 5)
        val content = if (sources.isEmpty()) {
            "Подходящих источников не найдено. Сначала запусти сканирование проекта."
        } else {
            sources.joinToString("\n\n") { source ->
                "${source.path}:${source.lineStart}-${source.lineEnd} sha256=${source.sha256}\n${source.content}"
            }
        }
        taskRepository.addArtifact(
            Artifact(UUID.randomUUID().toString(), task.id, "ragSources", content = content, createdAt = now()),
        )
        publish(task.id, EventLevel.INFO, "rag.sources", "Найдены локальные источники: ${sources.size}.")
        runModelExecutor(task, sources)
    }

    private suspend fun runAgentWorkflow(task: Task): Boolean {
        addStep(task, "research", "harness", "Подготовлен ограниченный контекст задачи.")
        addStep(task, "plan", "harness", "Построен план последовательности research → plan → execute → validate → report.")
        val sources = sourceRepository.search(task.projectId, task.input)
        runModelExecutor(task, sources)
        return true
    }

    fun resumeAfterApproval(taskId: String) {
        if (jobs[taskId]?.isActive == true) return
        jobs[taskId] = scope.launch {
            runCatching {
                publish(taskId, EventLevel.INFO, "approval.accepted", "Подтверждение принято; выполняется разрешённый шаг.")
                execute(taskId)
            }.onFailure { error ->
                if (error !is CancellationException) fail(taskId, error.message ?: "Неизвестная ошибка исполнения")
            }
        }
    }

    private suspend fun runModelExecutor(task: Task, sources: List<SourceDocument>) {
        addStep(task, "execute", "model-provider", "Запущен выбранный провайдер модели.")
        val result = modelProvider.complete(
            requireNotNull(task.modelProfileId) { "Для задачи не выбран профиль модели." },
            ModelCompletionRequest(
                prompt = prompt(task, sources),
                externalContextApproved = task.mode == TaskMode.MAY_MODIFY || task.pendingApprovalKind == null,
            ),
        )
        taskRepository.addArtifact(
            Artifact(UUID.randomUUID().toString(), task.id, "modelReport", content = result.content, createdAt = now()),
        )
        addStep(task, "validate", "harness", "Ответ модели получен без запуска внешних команд.")
        addStep(task, "report", "model-provider", "Ответ модели сохранён как артефакт.")
    }

    private fun prompt(task: Task, sources: List<SourceDocument>): String = buildString {
        appendLine("Ты помогаешь с инженерной задачей в проекте. Ответь по-русски, кратко и предметно.")
        appendLine("Не предлагай выполнять команды, не публикуй результаты и не раскрывай секреты.")
        appendLine("Отвечай только по проверенным фрагментам ниже. После каждого утверждения указывай источник в виде [путь:строки].")
        appendLine("Если в источниках нет достаточного ответа, прямо скажи: «Не знаю: в найденной документации недостаточно информации.»")
        appendLine()
        appendLine("Задача:")
        appendLine(task.input)
        if (sources.isNotEmpty()) {
            appendLine()
            appendLine("Проверенные локальные фрагменты проекта:")
            sources.forEach { source ->
                appendLine("Файл ${source.path}:${source.lineStart}-${source.lineEnd}")
                appendLine(source.content)
            }
        }
    }.take(30_000)

    private suspend fun addStep(task: Task, stage: String, executor: String, result: String) {
        val time = now()
        taskRepository.addStep(
            TaskStep(UUID.randomUUID().toString(), task.id, stage, executor, TaskStatus.COMPLETED, task.input, result, time, time),
        )
        publish(task.id, EventLevel.INFO, "step.$stage", result)
    }

    private suspend fun fail(taskId: String, message: String) {
        taskRepository.updateStatus(taskId, TaskStatus.FAILED, now())
        publish(taskId, EventLevel.ERROR, "task.failed", message)
    }

    private suspend fun publish(taskId: String, level: EventLevel, type: String, message: String) {
        val event = TaskEvent(UUID.randomUUID().toString(), taskId, now(), level, type, message)
        taskRepository.addEvent(event)
        events.publish(event)
    }

    private fun now(): Long = System.currentTimeMillis()
    private companion object { val TERMINAL_STATUSES = setOf(TaskStatus.COMPLETED, TaskStatus.FAILED, TaskStatus.CANCELLED) }
}
