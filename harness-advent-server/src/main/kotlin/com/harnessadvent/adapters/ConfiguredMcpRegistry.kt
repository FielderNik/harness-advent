package com.harnessadvent.adapters

import com.harnessadvent.bootstrap.McpServerConnection
import com.harnessadvent.domain.McpRegistry
import com.harnessadvent.domain.McpServer
import com.harnessadvent.domain.McpTool
import com.harnessadvent.domain.McpToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedReader
import java.io.BufferedWriter

/**
 * Read-only MCP client for configured stdio servers. It never executes a shell
 * and exposes only the server/tool/repository allowlists from local config.
 */
class ConfiguredMcpRegistry(
    connections: List<McpServerConnection>,
    private val client: StdioMcpClient = StdioMcpClient(),
) : McpRegistry {
    private val connectionsById = connections.associateBy(McpServerConnection::id)

    override suspend fun servers(): List<McpServer> = connectionsById.values.map { connection ->
        McpServer(
            id = connection.id,
            name = connection.name,
            enabled = connection.isConfigured,
            readOnly = connection.readOnly,
            allowedRepositories = connection.allowedRepositories,
        )
    }

    override suspend fun tools(serverId: String): List<McpTool> {
        val connection = configured(serverId)
        return client.listTools(connection)
            .filter { it.name in connection.allowedTools }
    }

    override suspend fun call(serverId: String, toolName: String, arguments: JsonObject): McpToolResult {
        val connection = configured(serverId)
        require(toolName in connection.allowedTools) { "Инструмент MCP не разрешён политикой сервера." }
        return client.call(connection, toolName, GitHubRepositoryPolicy.restrict(connection, arguments))
    }

    private fun configured(serverId: String): McpServerConnection = requireNotNull(connectionsById[serverId]) {
        "MCP-сервер не найден."
    }.also { require(it.isConfigured) { "MCP-сервер не настроен безопасно." } }

}

object GitHubRepositoryPolicy {
    fun restrict(connection: McpServerConnection, arguments: JsonObject): JsonObject {
        if (connection.id != "github") return arguments
        val expectedRepository = connection.allowedRepositories.single()
        val expected = expectedRepository.split('/', limit = 2)
        require(expected.size == 2 && expected.all(String::isNotBlank)) { "Некорректный allowlist GitHub-репозитория." }
        val owner = arguments["owner"]?.jsonPrimitive?.contentOrNull
        val repository = arguments["repo"]?.jsonPrimitive?.contentOrNull
        val combined = arguments["repository"]?.jsonPrimitive?.contentOrNull
        require(owner == null || owner == expected[0]) { "GitHub MCP ограничен другим репозиторием." }
        require(repository == null || repository == expected[1]) { "GitHub MCP ограничен другим репозиторием." }
        require(combined == null || combined == expectedRepository) { "GitHub MCP ограничен другим репозиторием." }
        return JsonObject(arguments + mapOf(
            "owner" to JsonPrimitive(owner ?: expected[0]),
            "repo" to JsonPrimitive(repository ?: expected[1]),
        ))
    }
}

class StdioMcpClient(
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    suspend fun listTools(connection: McpServerConnection): List<McpTool> = request(connection, "tools/list", JsonObject(emptyMap()))
        .jsonObject["tools"]?.jsonArray.orEmpty().mapNotNull { item ->
            val tool = item.jsonObject
            val name = tool["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            McpTool(name, tool["description"]?.jsonPrimitive?.contentOrNull, tool["inputSchema"]?.jsonObject ?: JsonObject(emptyMap()))
        }

    suspend fun call(connection: McpServerConnection, toolName: String, arguments: JsonObject): McpToolResult {
        val result = request(connection, "tools/call", JsonObject(mapOf(
            "name" to JsonPrimitive(toolName),
            "arguments" to arguments,
        )))
        val content = result.jsonObject["content"]?.toString().orEmpty()
        return McpToolResult(content = content, isError = result.jsonObject["isError"]?.jsonPrimitive?.contentOrNull == "true")
    }

    private suspend fun request(connection: McpServerConnection, method: String, params: JsonObject): JsonElement =
        withTimeout(connection.timeoutSeconds * 1_000) {
            withContext(Dispatchers.IO) {
                val process = ProcessBuilder(listOf(connection.command) + connection.arguments)
                    .redirectErrorStream(true)
                    .apply { environment().putAll(connection.environment) }
                    .start()
                try {
                    process.outputStream.bufferedWriter().use { writer ->
                        process.inputStream.bufferedReader().use { reader ->
                            send(writer, "1", "initialize", JsonObject(mapOf(
                                "protocolVersion" to JsonPrimitive("2025-06-18"),
                                "capabilities" to JsonObject(emptyMap()),
                                "clientInfo" to JsonObject(mapOf("name" to JsonPrimitive("harness-advent"), "version" to JsonPrimitive("1.0"))),
                            )))
                            awaitResponse(reader, "1")
                            writer.write(json.encodeToString(JsonObject.serializer(), JsonObject(mapOf(
                                "jsonrpc" to JsonPrimitive("2.0"),
                                "method" to JsonPrimitive("notifications/initialized"),
                            ))))
                            writer.newLine()
                            writer.flush()
                            send(writer, "2", method, params)
                            awaitResponse(reader, "2").jsonObject["result"]
                                ?: throw IllegalArgumentException("MCP-сервер вернул ошибку инструмента.")
                        }
                    }
                } finally {
                    process.destroyForcibly()
                }
            }
        }

    private fun send(writer: BufferedWriter, id: String, method: String, params: JsonObject) {
        writer.write(json.encodeToString(JsonObject.serializer(), JsonObject(mapOf(
            "jsonrpc" to JsonPrimitive("2.0"),
            "id" to JsonPrimitive(id),
            "method" to JsonPrimitive(method),
            "params" to params,
        ))))
        writer.newLine()
        writer.flush()
    }

    private fun awaitResponse(reader: BufferedReader, id: String): JsonObject {
        while (true) {
            val line = reader.readLine() ?: throw IllegalArgumentException("MCP-сервер завершился без ответа.")
            val response = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull() ?: continue
            if (response["id"]?.jsonPrimitive?.contentOrNull == id) return response
        }
    }
}
