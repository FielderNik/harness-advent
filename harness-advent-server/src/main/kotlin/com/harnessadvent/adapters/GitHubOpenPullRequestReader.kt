package com.harnessadvent.adapters

import com.harnessadvent.bootstrap.TestGenerationConfig
import com.harnessadvent.domain.OpenPullRequestReader
import com.harnessadvent.domain.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** Read-only GitHub запросы: только пути файлов из открытых PR, без diff и содержимого проекта. */
class GitHubOpenPullRequestReader(private val config: TestGenerationConfig) : OpenPullRequestReader {
    override suspend fun changedFiles(project: Project): Set<String> = withContext(Dispatchers.IO) {
        val repository = requireNotNull(config.allowedRepository)
        val pulls = get("https://api.github.com/repos/$repository/pulls?state=open&per_page=100")
        Json.parseToJsonElement(pulls).jsonArrayOrEmpty().flatMap { pull ->
            val number = pull.jsonObject["number"]?.jsonPrimitive?.content ?: return@flatMap emptyList()
            val files = get("https://api.github.com/repos/$repository/pulls/$number/files?per_page=100")
            Json.parseToJsonElement(files).jsonArrayOrEmpty().mapNotNull { file ->
                file.jsonObject["filename"]?.jsonPrimitive?.content
            }
        }.toSet()
    }

    private fun get(url: String): String {
        val request = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(15))
            .header("Accept", "application/vnd.github+json")
            .header("Authorization", "Bearer ${config.githubToken}")
            .GET().build()
        val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
        require(response.statusCode() in 200..299) { "Не удалось прочитать открытые pull request с GitHub (HTTP ${response.statusCode()})." }
        return response.body()
    }
}

private fun kotlinx.serialization.json.JsonElement.jsonArrayOrEmpty(): List<kotlinx.serialization.json.JsonElement> =
    this as? kotlinx.serialization.json.JsonArray ?: emptyList()
