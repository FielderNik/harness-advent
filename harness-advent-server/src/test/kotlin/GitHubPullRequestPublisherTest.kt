package com.harnessadvent

import com.harnessadvent.adapters.GitHubPullRequestPublisher
import com.harnessadvent.bootstrap.TestGenerationConfig
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class GitHubPullRequestPublisherTest {
    @Test
    fun `creates a pull request in the configured repository`() = runBlocking {
        var authorization = ""
        var requestBody = ""
        val server = HttpServer.create(InetSocketAddress(0), 0).apply {
            createContext("/repos/owner/repository/pulls") { exchange ->
                authorization = exchange.requestHeaders.getFirst("Authorization").orEmpty()
                requestBody = exchange.requestBody.bufferedReader().use { it.readText() }
                val response = """{"html_url":"https://github.test/owner/repository/pull/7","number":7}"""
                exchange.sendResponseHeaders(201, response.toByteArray().size.toLong())
                exchange.responseBody.use { it.write(response.toByteArray()) }
            }
            start()
        }
        try {
            val publisher = GitHubPullRequestPublisher(
                TestGenerationConfig(true, "test-token", "owner/repository", "main", listOf("./gradlew", "test")),
                "http://127.0.0.1:${server.address.port}",
            )

            val result = publisher.create("harness/tests/profile-repository", "com.example.ProfileRepositoryImpl")

            assertEquals("https://github.test/owner/repository/pull/7", result.url)
            assertEquals(7, result.number)
            assertEquals("Bearer test-token", authorization)
            assertContains(requestBody, "\"head\":\"harness/tests/profile-repository\"")
            assertContains(requestBody, "\"base\":\"main\"")
        } finally {
            server.stop(0)
        }
    }
}
