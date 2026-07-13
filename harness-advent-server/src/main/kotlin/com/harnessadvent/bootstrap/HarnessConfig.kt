package com.harnessadvent.bootstrap

import com.harnessadvent.domain.ContextPolicy
import com.harnessadvent.domain.ModelProfile
import java.nio.file.Path
import kotlin.io.path.absolute

data class HarnessConfig(
    val databaseUrl: String,
    val allowedProjectPaths: Set<Path>,
    val modelProfiles: List<ModelProfile>,
) {
    companion object {
        fun fromEnvironment(environment: Map<String, String> = System.getenv()): HarnessConfig {
            val paths = environment["HARNESS_ALLOWED_PROJECTS"]
                .orEmpty()
                .split(',')
                .map(String::trim)
                .filter(String::isNotEmpty)
                .map { Path.of(it).toAbsolutePath().normalize() }
                .toSet()
            return HarnessConfig(
                databaseUrl = environment["HARNESS_DATABASE_URL"] ?: "jdbc:sqlite:./harness-advent.db",
                allowedProjectPaths = paths,
                modelProfiles = listOf(
                    ModelProfile(
                        id = "local",
                        provider = "OpenAI-compatible local provider",
                        endpointConfigured = !environment["HARNESS_LOCAL_MODEL_ENDPOINT"].isNullOrBlank(),
                        models = environment["HARNESS_LOCAL_MODELS"].orEmpty().split(',').filter(String::isNotBlank),
                        contextPolicy = ContextPolicy.LOCAL_ONLY,
                        capabilities = setOf("chat", "stream", "cancel"),
                    ),
                    profileFromEnvironment(
                        id = "deepseek",
                        provider = "DeepSeek OpenAI-compatible API",
                        endpoint = environment["HARNESS_DEEPSEEK_ENDPOINT"],
                        models = environment["HARNESS_DEEPSEEK_MODELS"],
                    ),
                    profileFromEnvironment(
                        id = "openrouter",
                        provider = "OpenRouter OpenAI-compatible API",
                        endpoint = environment["HARNESS_OPENROUTER_ENDPOINT"],
                        models = environment["HARNESS_OPENROUTER_MODELS"],
                    ),
                ),
            )
        }

        private fun profileFromEnvironment(id: String, provider: String, endpoint: String?, models: String?) = ModelProfile(
            id = id,
            provider = provider,
            endpointConfigured = !endpoint.isNullOrBlank(),
            models = models.orEmpty().split(',').map(String::trim).filter(String::isNotEmpty),
            contextPolicy = ContextPolicy.APPROVED_TASK_CONTEXT,
            capabilities = setOf("chat", "stream", "cancel"),
        )
    }
}
