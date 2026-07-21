package com.harnessadvent.application

import com.harnessadvent.domain.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * Правила MVP намеренно узкие: это Kotlin-реализации репозиториев по соглашению
 * `*RepositoryImpl.kt`. Они не пытаются подменить полноценный анализ Kotlin PSI.
 */
class TestCoverageService(
    private val projects: ProjectRepository,
    private val plans: TestCoveragePlanRepository,
    private val files: ProjectFiles,
    private val pullRequests: OpenPullRequestReader,
    private val tasks: TaskService,
    private val taskRepository: TaskRepository,
) {
    suspend fun get(projectId: String): TestCoveragePlan = requireNotNull(plans.findByProject(projectId)) {
        "План покрытия ещё не создан. Нажмите «Написать тесты»."
    }

    suspend fun refresh(projectId: String): TestCoveragePlan {
        val project = requireNotNull(projects.find(projectId)) { "Проект не найден." }
        val previous = plans.findByProject(projectId)
        val planId = previous?.id ?: UUID.randomUUID().toString()
        val filesInProject = files.list(project, limit = 1_000)
        val tests = filesInProject.filter { it.endsWith("Test.kt") || it.endsWith("Tests.kt") }
        val openPullRequestFiles = pullRequests.changedFiles(project)
        val previousByClass = previous?.items.orEmpty().associateBy(TestCoveragePlanItem::className)
        val now = System.currentTimeMillis()
        val candidates = filesInProject
            .filter { it.contains("/data/repository/") && it.endsWith("RepositoryImpl.kt") }
            .mapNotNull { sourcePath -> repositoryCandidate(project, sourcePath, tests, openPullRequestFiles) }
        val items = candidates
            .asSequence()
            .sortedBy { it.className }
            .map { candidate ->
                val old = previousByClass[candidate.className]
                val covered = candidate.covered
                val status = when {
                    covered -> TestCoverageStatus.COVERED
                    candidate.testPath in openPullRequestFiles -> TestCoverageStatus.COVERED_IN_OPEN_PR
                    old?.status in PRESERVED_STATUSES -> requireNotNull(old).status
                    else -> TestCoverageStatus.NEEDS_TESTS
                }
                TestCoveragePlanItem(
                    id = old?.id ?: UUID.randomUUID().toString(),
                    planId = planId,
                    className = candidate.className,
                    sourcePath = candidate.sourcePath,
                    testPath = candidate.testPath,
                    status = status,
                    reason = when {
                        covered -> "Найден unit-тест ${candidate.testPath}."
                        candidate.testPath in openPullRequestFiles -> "Тест изменяется в открытом pull request."
                        old?.reason != null && status in PRESERVED_STATUSES -> old.reason
                        else -> "Unit-тест для реализации репозитория не найден."
                    },
                    taskId = old?.taskId,
                    branch = old?.branch,
                    pullRequestUrl = old?.pullRequestUrl,
                    updatedAt = now,
                )
            }
            .toList()
        return plans.save(TestCoveragePlan(planId, projectId, TestCoverageTarget.REPOSITORIES, now, items))
    }

    suspend fun start(
        projectId: String,
        modelProfileId: String,
        author: String,
        idempotencyKey: String?,
    ): TestGenerationStartResult {
        val plan = refresh(projectId)
        val active = plan.items.firstOrNull { it.status in ACTIVE_STATUSES }
        require(active == null) { "Для класса ${active?.className} уже выполняется пайплайн." }
        val item = plan.items.firstOrNull { it.status == TestCoverageStatus.NEEDS_TESTS }
            ?: return TestGenerationStartResult(plan, null)
        val task = tasks.create(
            projectId = projectId,
            scenario = TaskScenario.TEST_GENERATION,
            mode = TaskMode.MAY_MODIFY,
            input = "Написать unit-тесты для ${item.className} (${item.sourcePath}).",
            modelProfileId = modelProfileId,
            author = author,
            idempotencyKey = idempotencyKey,
        )
        val claimed = item.copy(
            status = TestCoverageStatus.IN_PROGRESS,
            reason = "Класс выбран для пайплайна ${task.id}.",
            taskId = task.id,
            branch = "harness/tests/${item.className.substringAfterLast('.').removeSuffix("Impl").replace(Regex("([a-z])([A-Z])"), "\$1-\$2").lowercase()}-${task.id.take(8)}",
            updatedAt = System.currentTimeMillis(),
        )
        plans.updateItem(claimed)
        taskRepository.addArtifact(
            Artifact(
                id = UUID.randomUUID().toString(), taskId = task.id, type = "testCoveragePlanItem",
                content = Json.encodeToString(claimed), createdAt = System.currentTimeMillis(),
            ),
        )
        return TestGenerationStartResult(get(projectId), task)
    }

    private suspend fun repositoryCandidate(project: Project, sourcePath: String, tests: List<String>, openPullRequestFiles: Set<String>): Candidate? {
        val source = files.read(project, sourcePath)
        val name = CLASS_NAME.find(source)?.groupValues?.get(1) ?: return null
        val packageName = PACKAGE_NAME.find(source)?.groupValues?.get(1) ?: return null
        val expectedTest = sourcePath
            .replace(Regex("/src/[^/]+Main/"), "/src/commonTest/")
            .removeSuffix(".kt") + "Test.kt"
        val testPath = tests.firstOrNull { it == expectedTest || it.endsWith("/$name" + "Test.kt") } ?: expectedTest
        val covered = testPath in tests && files.read(project, testPath).contains(name)
        return Candidate("$packageName.$name", sourcePath, expectedTest, covered)
    }

    private data class Candidate(val className: String, val sourcePath: String, val testPath: String, val covered: Boolean)

    private companion object {
        val PACKAGE_NAME = Regex("(?m)^\\s*package\\s+([A-Za-z_][\\w.]*)")
        val CLASS_NAME = Regex("(?m)^\\s*(?:public\\s+|internal\\s+)?class\\s+(\\w+RepositoryImpl)\\b")
        val ACTIVE_STATUSES = setOf(
            TestCoverageStatus.IN_PROGRESS, TestCoverageStatus.TESTS_WRITTEN,
            TestCoverageStatus.CHECKING, TestCoverageStatus.AWAITING_PUBLICATION,
        )
        val PRESERVED_STATUSES = ACTIVE_STATUSES + setOf(
            TestCoverageStatus.PR_CREATED, TestCoverageStatus.COVERED_IN_OPEN_PR,
            TestCoverageStatus.BLOCKED,
        )
    }
}

@kotlinx.serialization.Serializable
data class TestGenerationStartResult(val plan: TestCoveragePlan, val task: Task?)
