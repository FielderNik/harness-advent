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
    @SerialName("codeReview") CODE_REVIEW,
    @SerialName("agentWorkflow") AGENT_WORKFLOW,
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
