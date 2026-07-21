package com.harnessadvent

import com.harnessadvent.adapters.LocalTestGenerationWorkspace
import com.harnessadvent.bootstrap.TestGenerationConfig
import com.harnessadvent.domain.TestWorkspace
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalTestGenerationWorkspaceTest {
    @Test
    fun `Gradle check stops after timeout`() = runBlocking {
        val root = Files.createTempDirectory("harness-gradle-check")
        val gradlew = root.resolve("gradlew")
        Files.writeString(gradlew, "#!/bin/sh\nsleep 5\n")
        assertTrue(gradlew.toFile().setExecutable(true))
        val workspace = LocalTestGenerationWorkspace(
            TestGenerationConfig(
                enabled = true,
                githubToken = "test-token",
                allowedRepository = "owner/repository",
                baseBranch = "main",
                checkCommand = listOf("./gradlew", "test"),
                checkTimeoutSeconds = 1,
            ),
        )
        val result = workspace.runChecks(TestWorkspace(root.toString(), "test", "src/commonTest/Test.kt"))

        assertFalse(result.successful)
        assertTrue(result.timedOut)
        assertContains(result.output, "превышен таймаут")
    }

    @Test
    fun `Gradle check returns output to task`() = runBlocking {
        val root = Files.createTempDirectory("harness-gradle-output")
        val gradlew = root.resolve("gradlew")
        Files.writeString(gradlew, "#!/bin/sh\necho checking\n")
        assertTrue(gradlew.toFile().setExecutable(true))
        val workspace = LocalTestGenerationWorkspace(
            TestGenerationConfig(true, "test-token", "owner/repository", "main", listOf("./gradlew", "test")),
        )
        val output = mutableListOf<String>()

        val result = workspace.runChecks(TestWorkspace(root.toString(), "test", "src/commonTest/Test.kt")) { output += it }

        assertTrue(result.successful)
        assertContains(result.output, "checking")
        assertTrue(output.any { it.contains("checking") })
    }

    @Test
    fun `Gradle check keeps compiler diagnostics from the end of verbose output`() = runBlocking {
        val root = Files.createTempDirectory("harness-gradle-tail")
        val gradlew = root.resolve("gradlew")
        Files.writeString(gradlew, "#!/bin/sh\nhead -c 60000 /dev/zero | tr '\\0' x\nprintf 'e: ProfileRepositoryImplTest.kt:81 nullable receiver\n'\nexit 1\n")
        assertTrue(gradlew.toFile().setExecutable(true))
        val workspace = LocalTestGenerationWorkspace(
            TestGenerationConfig(true, "test-token", "owner/repository", "main", listOf("./gradlew", "test")),
        )

        val result = workspace.runChecks(TestWorkspace(root.toString(), "test", "src/commonTest/Test.kt"))

        assertFalse(result.successful)
        assertContains(result.output, "nullable receiver")
        assertTrue(result.output.length <= 50_000)
    }
}
