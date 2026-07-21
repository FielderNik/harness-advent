package com.harnessadvent.adapters

import com.harnessadvent.domain.*

/** The initial server has no configured code-agent adapter and never runs external commands. */
class DisabledCodeAgentRunner : CodeAgentRunner {
    override suspend fun run(task: Task): AgentRunResult = AgentRunResult(
        summary = "Исполнитель агента не настроен: задача завершена без внешних команд и изменений файлов.",
    )
}

class DisabledMcpRegistry : McpRegistry {
    override suspend fun servers(): List<McpServer> = emptyList()

    override suspend fun tools(serverId: String): List<McpTool> =
        throw IllegalArgumentException("MCP-сервер не настроен.")

    override suspend fun call(serverId: String, toolName: String, arguments: kotlinx.serialization.json.JsonObject): McpToolResult =
        throw IllegalArgumentException("MCP-сервер не настроен.")
}

/** Network model calls are intentionally disabled until a profile and approved context manifest are wired in. */
class DisabledModelProvider : ModelProvider {
    override suspend fun health(profileId: String) = ModelProviderHealth(
        profileId = profileId,
        available = false,
        diagnostic = "Модельный адаптер не включён в локальном безопасном режиме.",
    )

    override suspend fun complete(profileId: String, request: ModelCompletionRequest): ModelCompletionResult {
        throw ModelProviderException("Модельный адаптер не включён.")
    }
}

class DisabledGitProvider : GitProvider {
    override val publishingEnabled: Boolean = false
}

class DisabledOpenPullRequestReader : OpenPullRequestReader {
    override suspend fun changedFiles(project: Project): Set<String> = emptySet()
}

class DisabledPullRequestPublisher : PullRequestPublisher {
    override suspend fun create(branch: String, className: String): PublishedPullRequest =
        throw IllegalStateException("Публикация pull request на GitHub не настроена.")
}

class DisabledTestGenerationWorkspace : TestGenerationWorkspace {
    private fun unavailable(): Nothing = throw IllegalStateException(
        "Генерация тестов отключена: настрой testGeneration.enabled и GitHub-токен в локальной конфигурации.",
    )

    override suspend fun create(project: Project, taskId: String, branch: String, testPath: String): TestWorkspace = unavailable()
    override suspend fun writeTest(workspace: TestWorkspace, content: String) = unavailable()
    override suspend fun runChecks(workspace: TestWorkspace, onOutput: suspend (String) -> Unit): TestCheckResult = unavailable()
    override suspend fun commit(workspace: TestWorkspace, className: String) = unavailable()
    override suspend fun push(workspace: TestWorkspace) = unavailable()
    override suspend fun cleanup(workspace: TestWorkspace) = Unit
}
