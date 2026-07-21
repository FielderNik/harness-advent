package com.harnessadvent.adapters

import com.harnessadvent.bootstrap.TestGenerationConfig
import com.harnessadvent.domain.PublishedPullRequest
import com.harnessadvent.domain.PullRequestPublisher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** Создаёт PR только для разрешённого репозитория и только после отдельного approval. */
class GitHubPullRequestPublisher(
    private val config: TestGenerationConfig,
    private val apiBaseUrl: String = "https://api.github.com",
    private val client: HttpClient = HttpClient.newHttpClient(),
) : PullRequestPublisher {
    override suspend fun create(branch: String, className: String): PublishedPullRequest = withContext(Dispatchers.IO) {
        require(branch.matches(Regex("harness/tests/[a-z0-9-]+"))) { "Недопустимое имя ветки для pull request." }
        val repository = requireNotNull(config.allowedRepository)
        val payload = Json.encodeToString(JsonObject.serializer(), buildJsonObject {
            put("title", JsonPrimitive("test: unit tests for ${className.substringAfterLast('.')}"))
            put("head", JsonPrimitive(branch))
            put("base", JsonPrimitive(config.baseBranch))
            put("body", JsonPrimitive("Создано Harness Advent после успешной разрешённой Gradle-проверки."))
        })
        val request = HttpRequest.newBuilder(URI("${apiBaseUrl.trimEnd('/')}/repos/$repository/pulls"))
            .timeout(Duration.ofSeconds(30))
            .header("Accept", "application/vnd.github+json")
            .header("Authorization", "Bearer ${requireNotNull(config.githubToken)}")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload))
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        require(response.statusCode() in 200..299) {
            "Не удалось создать pull request на GitHub (HTTP ${response.statusCode()})."
        }
        val result = Json.parseToJsonElement(response.body()).jsonObject
        val url = result["html_url"]?.jsonPrimitive?.contentOrNull
            ?: result["url"]?.jsonPrimitive?.contentOrNull
            ?: throw IllegalStateException("GitHub вернул ответ без URL pull request.")
        val number = result["number"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
        PublishedPullRequest(url, number)
    }
}
