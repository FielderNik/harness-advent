package com.harnessadvent.adapters

import com.harnessadvent.bootstrap.ModelConnection
import com.harnessadvent.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class OpenAiCompatibleModelProvider(
    private val connections: Map<String, ModelConnection>,
    private val httpClient: HttpClient = HttpClient.newBuilder().build(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) : ModelProvider {
    override suspend fun health(profileId: String): ModelProviderHealth {
        val connection = connections[profileId]
            ?: return ModelProviderHealth(profileId, false, "Профиль модели не найден.")
        return if (connection.isConfigured) {
            ModelProviderHealth(profileId, true, "Профиль настроен.")
        } else {
            ModelProviderHealth(profileId, false, "Не настроены endpoint, модель или токен доступа.")
        }
    }

    override suspend fun complete(profileId: String, request: ModelCompletionRequest): ModelCompletionResult {
        val connection = connections[profileId] ?: throw ModelProviderException("Профиль модели не найден.")
        require(connection.isConfigured) { "Профиль модели не настроен." }
        requireSafePrompt(request.prompt)
        if (connection.contextPolicy == ContextPolicy.APPROVED_TASK_CONTEXT) {
            require(request.externalContextApproved) { "Для облачной модели требуется подтверждение передачи контекста." }
        }
        val model = request.model ?: connection.models.first()
        require(model in connection.models) { "Указанная модель не разрешена в профиле." }
        val payload = json.encodeToString(
            ChatCompletionRequest.serializer(),
            ChatCompletionRequest(model = model, messages = listOf(ChatMessage(role = "user", content = request.prompt))),
        )
        val httpRequest = HttpRequest.newBuilder(chatCompletionsUri(connection.endpoint))
            .timeout(Duration.ofSeconds(connection.timeoutSeconds))
            .header("Content-Type", "application/json")
            .apply { connection.token?.let { header("Authorization", "Bearer $it") } }
            .POST(HttpRequest.BodyPublishers.ofString(payload))
            .build()
        val response = withContext(Dispatchers.IO) {
            httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString())
        }
        if (response.statusCode() !in 200..299) {
            throw ModelProviderException("Модель временно недоступна.")
        }
        val decoded = runCatching {
            json.decodeFromString(ChatCompletionResponse.serializer(), response.body())
        }.getOrElse {
            throw ModelProviderException("Модель вернула неподдерживаемый ответ.")
        }
        val content = decoded.choices.firstOrNull()?.message?.content?.trim().orEmpty()
        if (content.isEmpty()) throw ModelProviderException("Модель вернула пустой ответ.")
        return ModelCompletionResult(
            profileId = profileId,
            model = model,
            content = content,
            inputTokens = decoded.usage?.promptTokens,
            outputTokens = decoded.usage?.completionTokens,
        )
    }

    private fun chatCompletionsUri(endpoint: String): URI {
        val normalized = endpoint.trimEnd('/')
        require(normalized.startsWith("http://") || normalized.startsWith("https://")) { "Endpoint модели должен использовать HTTP(S)." }
        return URI.create(if (normalized.endsWith("/chat/completions")) normalized else "$normalized/chat/completions")
    }

    private fun requireSafePrompt(prompt: String) {
        require(prompt.isNotBlank() && prompt.length <= MAX_PROMPT_LENGTH) { "Запрос к модели должен содержать до $MAX_PROMPT_LENGTH символов." }
        require(!SECRET_ASSIGNMENT.containsMatchIn(prompt) && !PRIVATE_KEY.containsMatchIn(prompt)) {
            "Запрос похож на секрет и не будет отправлен модели."
        }
    }

    private companion object {
        const val MAX_PROMPT_LENGTH = 32_000
        val SECRET_ASSIGNMENT = Regex("(?i)(api[_-]?key|token|secret|password)\\s*[:=]\\s*\\S+")
        val PRIVATE_KEY = Regex("-----BEGIN [A-Z ]*PRIVATE KEY-----")
    }
}

@Serializable
private data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val stream: Boolean = false,
)

@Serializable
private data class ChatMessage(val role: String, val content: String)

@Serializable
private data class ChatCompletionResponse(val choices: List<ChatChoice>, val usage: ChatUsage? = null)

@Serializable
private data class ChatChoice(val message: ChatMessage)

@Serializable
private data class ChatUsage(
    @kotlinx.serialization.SerialName("prompt_tokens") val promptTokens: Long? = null,
    @kotlinx.serialization.SerialName("completion_tokens") val completionTokens: Long? = null,
)
