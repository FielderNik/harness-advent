package com.harnessadvent.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

@Serializable
data class Project(
    val id: String,
    val name: String,
    val path: String,
    val scanStatus: ProjectScanStatus,
    val lastScannedAt: Long? = null,
)

@Serializable
enum class ProjectScanStatus { NOT_SCANNED, READY, FAILED }

@Serializable
enum class TaskMode {
    @SerialName("readOnly") READ_ONLY,
    @SerialName("mayModify") MAY_MODIFY,
}

@Serializable
enum class TaskScenario {
    @SerialName("ragQuestion") RAG_QUESTION,
    @SerialName("supportAnswer") SUPPORT_ANSWER,
    @SerialName("codeReview") CODE_REVIEW,
    @SerialName("agentWorkflow") AGENT_WORKFLOW,
    @SerialName("testGeneration") TEST_GENERATION,
}

@Serializable
enum class TaskStatus {
    @SerialName("queued") QUEUED,
    @SerialName("running") RUNNING,
    @SerialName("waitingApproval") WAITING_APPROVAL,
    @SerialName("completed") COMPLETED,
    @SerialName("failed") FAILED,
    @SerialName("cancelled") CANCELLED,
}

@Serializable
data class Task(
    val id: String,
    val projectId: String,
    val scenario: TaskScenario,
    val mode: TaskMode,
    val status: TaskStatus,
    val author: String,
    val input: String,
    val createdAt: Long,
    val updatedAt: Long,
    val modelProfileId: String? = null,
    val pendingApprovalKind: ApprovalKind? = null,
)

@Serializable
data class TaskStep(
    val id: String,
    val taskId: String,
    val stage: String,
    val executor: String,
    val status: TaskStatus,
    val input: String,
    val result: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
data class TaskEvent(
    val id: String,
    val taskId: String,
    val occurredAt: Long,
    val level: EventLevel,
    val type: String,
    val message: String,
    val payload: String? = null,
)

@Serializable
enum class EventLevel { INFO, WARNING, ERROR }

@Serializable
data class Artifact(
    val id: String,
    val taskId: String,
    val type: String,
    val path: String? = null,
    val content: String? = null,
    val sha256: String? = null,
    val createdAt: Long,
)

@Serializable
enum class ApprovalKind {
    @SerialName("contextTransfer") CONTEXT_TRANSFER,
    @SerialName("commandExecution") COMMAND_EXECUTION,
    @SerialName("fileModification") FILE_MODIFICATION,
    @SerialName("externalPublication") EXTERNAL_PUBLICATION,
}

@Serializable
enum class ApprovalDecision {
    @SerialName("approved") APPROVED,
    @SerialName("rejected") REJECTED,
}

@Serializable
data class Approval(
    val id: String,
    val taskId: String,
    val kind: ApprovalKind,
    val decision: ApprovalDecision,
    val author: String,
    val createdAt: Long,
)

@Serializable
data class SourceDocument(
    val projectId: String,
    val path: String,
    val revision: String,
    val lineStart: Int,
    val lineEnd: Int,
    val sha256: String,
    val content: String,
)

/**
 * Immutable PR data received from CI. The diff and file names are treated as untrusted input,
 * not as instructions for the model.
 */
@Serializable
data class CodeReviewInput(
    val repository: String,
    val pullRequestNumber: Int,
    val pullRequestTitle: String,
    val headSha: String,
    val diff: String,
    val changedFiles: List<ChangedFile>,
)

@Serializable
data class ChangedFile(
    val path: String,
    val status: String,
    val previousPath: String? = null,
)

/**
 * Запрос поддержки использует тикет только как read-only контекст. Полное
 * содержимое тикета не хранится в самом задании: оно сокращается и очищается
 * перед сохранением в артефакте исполнителя.
 */
@Serializable
data class SupportAnswerRequest(
    val ticketId: String,
    val question: String,
)

/**
 * Результат ревью, который GitHub Action может опубликовать одним pull request review.
 * [comments] содержат только позиции правой стороны переданного diff.
 */
@Serializable
data class GitHubReview(
    val summary: String,
    val comments: List<GitHubReviewComment> = emptyList(),
)

@Serializable
data class GitHubReviewComment(
    val path: String,
    val line: Int,
    val severity: String,
    val body: String,
)

@Serializable
enum class ContextPolicy { LOCAL_ONLY, METADATA_ONLY, SELECTED_SOURCES, APPROVED_TASK_CONTEXT }

@Serializable
data class ModelProfile(
    val id: String,
    val provider: String,
    val endpointConfigured: Boolean,
    val models: List<String>,
    val contextPolicy: ContextPolicy,
    val capabilities: Set<String>,
)

/** Текущее покрытие одного production-класса тестами в рамках проекта. */
@Serializable
data class TestCoveragePlan(
    val id: String,
    val projectId: String,
    val target: TestCoverageTarget = TestCoverageTarget.REPOSITORIES,
    val analyzedAt: Long,
    val items: List<TestCoveragePlanItem> = emptyList(),
)

@Serializable
enum class TestCoverageTarget { REPOSITORIES }

@Serializable
enum class TestCoverageStatus {
    @SerialName("needsTests") NEEDS_TESTS,
    @SerialName("covered") COVERED,
    @SerialName("coveredInOpenPr") COVERED_IN_OPEN_PR,
    @SerialName("inProgress") IN_PROGRESS,
    @SerialName("testsWritten") TESTS_WRITTEN,
    @SerialName("checking") CHECKING,
    @SerialName("awaitingPublication") AWAITING_PUBLICATION,
    @SerialName("prCreated") PR_CREATED,
    @SerialName("failed") FAILED,
    @SerialName("blocked") BLOCKED,
}

@Serializable
data class TestCoveragePlanItem(
    val id: String,
    val planId: String,
    val className: String,
    val sourcePath: String,
    val testPath: String,
    val status: TestCoverageStatus,
    val reason: String? = null,
    val taskId: String? = null,
    val branch: String? = null,
    val pullRequestUrl: String? = null,
    val updatedAt: Long,
)
