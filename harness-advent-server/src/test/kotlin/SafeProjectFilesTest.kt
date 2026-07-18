package com.harnessadvent

import com.harnessadvent.adapters.AllowedProjectPolicy
import com.harnessadvent.adapters.SafeProjectFiles
import com.harnessadvent.domain.Project
import com.harnessadvent.domain.ProjectScanStatus
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class SafeProjectFilesTest {
    @Test
    fun `write rejects paths outside the registered project`() = runBlocking {
        val projectRoot = Files.createTempDirectory("harness-project")
        val outside = projectRoot.parent.resolve("harness-outside-${System.nanoTime()}.md")
        val project = Project("project", "Fixture", projectRoot.toString(), ProjectScanStatus.NOT_SCANNED)
        val files = SafeProjectFiles(AllowedProjectPolicy(setOf(projectRoot.toAbsolutePath().normalize())))

        assertFailsWith<IllegalArgumentException> {
            files.write(project, "../${outside.fileName}", "outside")
        }

        assertFalse(Files.exists(outside))
    }
}
