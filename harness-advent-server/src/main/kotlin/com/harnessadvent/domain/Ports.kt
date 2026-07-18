package com.harnessadvent.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

interface ProjectRepository {
    suspend fun upsert(project: Project): Project
    suspend fun list(): List<Project>
    suspend fun find(id: String): Project?
}

interface TaskRepository {
    suspend fun create(task: Task, idempotencyKey: String?): Task
    suspend fun find(id: String): Task?
    suspend fun findByIdempotencyKey(key: String): Task?
    suspend fun list(): List<Task>
    suspend fun updateStatus(id: String, status: TaskStatus, updatedAt: Long): Task
    suspend fun updatePendingApproval(id: String, kind: ApprovalKind?, updatedAt: Long): Task
    suspend fun addStep(step: TaskStep): TaskStep
    suspend fun addEvent(event: TaskEvent): TaskEvent
    suspend fun events(taskId: String): List<TaskEvent>
    suspend fun addArtifact(artifact: Artifact): Artifact
    suspend fun artifacts(taskId: String): List<Artifact>
    suspend fun addApproval(approval: Approval): Approval
}

interface SourceRepository {
    suspend fun replaceForProject(projectId: String, documents: List<SourceDocument>)
    suspend fun search(projectId: String, query: String, limit: Int = 10): List<SourceDocument>
}

interface ProjectFiles {
    suspend fun list(project: Project, limit: Int = 300): List<String>
    suspend fun search(project: Project, query: String, limit: Int = 50): List<FileSearchMatch>
    suspend fun read(project: Project, path: String): String
    suspend fun write(project: Project, path: String, content: String): FileWriteResult
}

data class FileSearchMatch(val path: String, val line: Int, val content: String)
data class FileWriteResult(val path: String, val previousContent: String?, val content: String)

interface CodeAgentRunner {
    suspend fun run(task: Task): AgentRunResult
}

interface ModelProvider {
    suspend fun health(profileId: String): ModelProviderHealth
    suspend fun complete(profileId: String, request: ModelCompletionRequest): ModelCompletionResult
}

data class ModelProviderHealth(val profileId: String, val available: Boolean, val diagnostic: String)

@Serializable
data class ModelCompletionRequest(
    val model: String? = null,
    val prompt: String,
    val externalContextApproved: Boolean = false,
)

@Serializable
data class ModelCompletionResult(
    val profileId: String,
    val model: String,
    val content: String,
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
)

class ModelProviderException(message: String) : RuntimeException(message)

data class AgentRunResult(val summary: String, val usage: AgentUsage? = null)
data class AgentUsage(val inputTokens: Long, val outputTokens: Long)

interface McpRegistry {
    suspend fun servers(): List<McpServer>
    suspend fun tools(serverId: String): List<McpTool>
    suspend fun call(serverId: String, toolName: String, arguments: JsonObject): McpToolResult
}

@Serializable
data class McpServer(
    val id: String,
    val name: String,
    val enabled: Boolean,
    val readOnly: Boolean,
    val allowedRepositories: Set<String>,
)

@Serializable
data class McpTool(
    val name: String,
    val description: String? = null,
    val inputSchema: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class McpToolResult(
    val content: String,
    val isError: Boolean = false,
)

interface GitProvider {
    val publishingEnabled: Boolean
}
