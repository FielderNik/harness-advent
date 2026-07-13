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
