package com.harnessadvent.bootstrap

import com.harnessadvent.domain.ContextPolicy
import com.harnessadvent.domain.ModelProfile
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

data class ModelConnection(
    val id: String,
    val endpoint: String,
    val token: String?,
    val models: List<String>,
    val contextPolicy: ContextPolicy,
    val timeoutSeconds: Long,
) {
    val isConfigured: Boolean
        get() = endpoint.isNotBlank() && (id == "local" || !token.isNullOrBlank()) && models.isNotEmpty()
}

data class McpServerConnection(
    val id: String,
    val name: String,
    val command: String,
    val arguments: List<String>,
    val environment: Map<String, String>,
    val readOnly: Boolean,
    val allowedRepositories: Set<String>,
    val allowedTools: Set<String>,
    val timeoutSeconds: Long,
) {
    val isConfigured: Boolean
        get() = command.isNotBlank() && arguments.isNotEmpty() && readOnly && allowedTools.isNotEmpty()
}

data class HarnessConfig(
    val databaseUrl: String,
    val allowedProjectPaths: Set<Path>,
    val modelProfiles: List<ModelProfile>,
    val modelConnections: Map<String, ModelConnection> = emptyMap(),
    val mcpServers: List<McpServerConnection> = emptyList(),
    val codeReviewApiToken: String? = null,
    val codeReviewAutoApprovedContextProfiles: Set<String> = emptySet(),
) {
    companion object {
        private const val DEFAULT_CONFIG_FILE = "harness.local.properties"
        private val modelIds = listOf("local", "deepseek", "openrouter")

        fun load(): HarnessConfig {
            val configPath = System.getenv("HARNESS_CONFIG_FILE")
                ?.takeIf(String::isNotBlank)
                ?.let(Path::of)
                ?: Path.of(DEFAULT_CONFIG_FILE)
            return fromFile(configPath)
        }

        fun fromFile(configPath: Path): HarnessConfig {
            val properties = Properties()
            if (Files.isRegularFile(configPath)) {
                Files.newInputStream(configPath).use(properties::load)
            }
            return fromProperties(properties)
        }

        fun fromProperties(properties: Properties): HarnessConfig {
            // Контейнерный запуск задаёт эту границу окружением. Приоритет над
            // локальным файлом конфигурации не позволяет расширить область
            // доступных проектов значением из смонтированного config-файла.
            val allowedProjects = System.getenv("HARNESS_ALLOWED_PROJECTS")?.takeIf(String::isNotBlank)
                ?: properties.getProperty("HARNESS_ALLOWED_PROJECTS")
                ?: properties.getProperty("server.allowedProjectPaths")
            val paths = allowedProjects
                .orEmpty()
                .split(',')
                .map(String::trim)
                .filter(String::isNotEmpty)
                .map { Path.of(it).toAbsolutePath().normalize() }
                .toSet()
            val connections = modelIds.associateWith { id -> modelConnection(properties, id) }
            val autoApprovedProfiles = properties.getProperty("codeReview.autoApproveContextProfiles").orEmpty()
                .split(',').map(String::trim).filter(String::isNotEmpty).toSet()
            require(autoApprovedProfiles.all { it in modelIds - "local" }) {
                "Автоподтверждение передачи контекста для code review доступно только облачным профилям: ${modelIds.filter { it != "local" }.joinToString()}."
            }
            val mcpServers = properties.getProperty("mcp.servers").orEmpty()
                .split(',')
                .map(String::trim)
                .filter(String::isNotEmpty)
                .distinct()
                .map { id -> mcpServerConnection(properties, id) }
            return HarnessConfig(
                databaseUrl = System.getenv("HARNESS_DATABASE_URL")?.takeIf(String::isNotBlank)
                    ?: properties.getProperty("server.databaseUrl")
                    ?: "jdbc:sqlite:./harness-advent.db",
                allowedProjectPaths = paths,
                modelProfiles = modelIds.map { id -> connections.getValue(id).toPublicProfile(providerName(id)) },
                modelConnections = connections,
                mcpServers = mcpServers,
                codeReviewApiToken = properties.getProperty("codeReview.apiToken")?.trim()?.takeIf(String::isNotEmpty),
                codeReviewAutoApprovedContextProfiles = autoApprovedProfiles,
            )
        }

        private fun modelConnection(properties: Properties, id: String): ModelConnection = ModelConnection(
            id = id,
            endpoint = properties.getProperty("models.$id.endpoint").orEmpty().trim(),
            token = properties.getProperty("models.$id.token")?.trim()?.takeIf(String::isNotEmpty),
            models = properties.getProperty("models.$id.models").orEmpty().split(',').map(String::trim).filter(String::isNotEmpty),
            contextPolicy = if (id == "local") ContextPolicy.LOCAL_ONLY else ContextPolicy.APPROVED_TASK_CONTEXT,
            timeoutSeconds = properties.getProperty("models.$id.timeoutSeconds")?.toLongOrNull()?.coerceIn(1, 120) ?: 60,
        )

        private fun providerName(id: String): String = when (id) {
            "local" -> "OpenAI-compatible local provider"
            "deepseek" -> "DeepSeek OpenAI-compatible API"
            "openrouter" -> "OpenRouter OpenAI-compatible API"
            else -> id
        }

        private fun mcpServerConnection(properties: Properties, id: String): McpServerConnection {
            val prefix = "mcp.$id."
            val environment = properties.stringPropertyNames()
                .filter { it.startsWith("${prefix}env.") }
                .associate { key -> key.removePrefix("${prefix}env.") to properties.getProperty(key).trim() }
            val allowedRepositories = properties.getProperty("${prefix}allowedRepositories").orEmpty()
                .split(',').map(String::trim).filter(String::isNotEmpty).toSet()
            val connection = McpServerConnection(
                id = id,
                name = properties.getProperty("${prefix}name")?.trim()?.takeIf(String::isNotEmpty) ?: id,
                command = properties.getProperty("${prefix}command").orEmpty().trim(),
                arguments = properties.getProperty("${prefix}arguments").orEmpty().split(',').map(String::trim).filter(String::isNotEmpty),
                environment = environment,
                readOnly = properties.getProperty("${prefix}readOnly")?.toBooleanStrictOrNull() ?: false,
                allowedRepositories = allowedRepositories,
                allowedTools = properties.getProperty("${prefix}allowedTools").orEmpty()
                    .split(',').map(String::trim).filter(String::isNotEmpty).toSet(),
                timeoutSeconds = properties.getProperty("${prefix}timeoutSeconds")?.toLongOrNull()?.coerceIn(1, 120) ?: 30,
            )
            if (id == "github" && connection.isConfigured) {
                require(connection.allowedRepositories.size == 1) {
                    "GitHub MCP должен быть ограничен ровно одним репозиторием owner/repository."
                }
                require(connection.environment["GITHUB_READ_ONLY"] == "1") {
                    "GitHub MCP требует GITHUB_READ_ONLY=1."
                }
            }
            return connection
        }
    }
}

private fun ModelConnection.toPublicProfile(provider: String) = ModelProfile(
    id = id,
    provider = provider,
    endpointConfigured = isConfigured,
    models = models,
    contextPolicy = contextPolicy,
    capabilities = setOf("chat", "stream", "cancel"),
)
