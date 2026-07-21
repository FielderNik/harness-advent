package com.harnessadvent.adapters

import com.harnessadvent.bootstrap.TestGenerationConfig
import com.harnessadvent.domain.McpRegistry
import com.harnessadvent.domain.PublishedPullRequest
import com.harnessadvent.domain.PullRequestPublisher
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Единственная внешняя публикация сценария: allowlisted GitHub MCP create_pull_request. */
class GitHubMcpPullRequestPublisher(
    private val mcp: McpRegistry,
    private val config: TestGenerationConfig,
) : PullRequestPublisher {
    override suspend fun create(branch: String, className: String): PublishedPullRequest {
        require(branch.matches(Regex("harness/tests/[a-z0-9-]+"))) { "Недопустимое имя ветки для pull request." }
        val result = mcp.call(
            "github",
            "create_pull_request",
            buildJsonObject {
                put("title", JsonPrimitive("test: unit tests for ${className.substringAfterLast('.')}"))
                put("head", JsonPrimitive(branch))
                put("base", JsonPrimitive(config.baseBranch))
                put("body", JsonPrimitive("Создано Harness Advent после успешной разрешённой Gradle-проверки."))
            },
        )
        require(!result.isError) { "GitHub MCP не создал pull request." }
        val payload = result.content.textFromMcp()
        val response = Json.parseToJsonElement(payload).jsonObject
        val url = response["html_url"]?.jsonPrimitive?.contentOrNull
            ?: response["url"]?.jsonPrimitive?.contentOrNull
            ?: throw IllegalStateException("GitHub MCP вернул ответ без URL pull request.")
        val number = response["number"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
        return PublishedPullRequest(url, number)
    }

    private fun String.textFromMcp(): String {
        val blocks = runCatching { Json.parseToJsonElement(this) }.getOrNull() as? JsonArray
            ?: return this
        return blocks.firstNotNullOfOrNull { block ->
            val objectBlock = block as? JsonObject ?: return@firstNotNullOfOrNull null
            if (objectBlock["type"]?.jsonPrimitive?.contentOrNull == "text") {
                objectBlock["text"]?.jsonPrimitive?.contentOrNull
            } else null
        } ?: throw IllegalStateException("GitHub MCP вернул ответ без текстового содержимого.")
    }
}
