package com.harnessadvent.domain

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

interface CodeAgentRunner {
    suspend fun run(task: Task): AgentRunResult
}

interface ModelProvider {
    suspend fun health(profileId: String): ModelProviderHealth
}

data class ModelProviderHealth(val profileId: String, val available: Boolean, val diagnostic: String)

data class AgentRunResult(val summary: String, val usage: AgentUsage? = null)
data class AgentUsage(val inputTokens: Long, val outputTokens: Long)

interface McpRegistry {
    fun allowedTools(): Set<String>
}

interface GitProvider {
    val publishingEnabled: Boolean
}
