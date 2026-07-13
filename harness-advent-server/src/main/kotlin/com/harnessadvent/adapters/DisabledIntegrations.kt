package com.harnessadvent.adapters

import com.harnessadvent.domain.*

/** The initial server has no configured code-agent adapter and never runs external commands. */
class DisabledCodeAgentRunner : CodeAgentRunner {
    override suspend fun run(task: Task): AgentRunResult = AgentRunResult(
        summary = "Исполнитель агента не настроен: задача завершена без внешних команд и изменений файлов.",
    )
}

class DisabledMcpRegistry : McpRegistry {
    override fun allowedTools(): Set<String> = emptySet()
}

/** Network model calls are intentionally disabled until a profile and approved context manifest are wired in. */
class DisabledModelProvider : ModelProvider {
    override suspend fun health(profileId: String) = ModelProviderHealth(
        profileId = profileId,
        available = false,
        diagnostic = "Модельный адаптер не включён в локальном безопасном режиме.",
    )
}

class DisabledGitProvider : GitProvider {
    override val publishingEnabled: Boolean = false
}
