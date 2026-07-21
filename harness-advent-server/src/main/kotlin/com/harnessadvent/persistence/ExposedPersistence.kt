package com.harnessadvent.persistence

import com.harnessadvent.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update

class HarnessDatabase(databaseUrl: String) {
    val database: Database = Database.connect(databaseUrl, driver = "org.sqlite.JDBC")

    init {
        transaction(database) {
            SchemaMigrations.apply(this)
        }
    }
}

private object SchemaMigrations {
    fun apply(transaction: JdbcTransaction) {
        SchemaUtils.create(SchemaVersionsTable)
        if (SchemaVersionsTable.selectAll().empty()) {
            SchemaUtils.create(
                ProjectsTable,
                TasksTable,
                TaskStepsTable,
                TaskEventsTable,
                ArtifactsTable,
                ApprovalsTable,
                SourceDocumentsTable,
                RagSourceChunksTable,
                TestCoveragePlansTable,
                TestCoveragePlanItemsTable,
            )
            SchemaVersionsTable.insert { it[version] = 4 }
            return
        }
        val currentVersion = SchemaVersionsTable.selectAll().maxOf { it[SchemaVersionsTable.version] }
        if (currentVersion < 2) {
            transaction.exec("ALTER TABLE tasks ADD COLUMN model_profile_id VARCHAR(64)")
            transaction.exec("ALTER TABLE tasks ADD COLUMN pending_approval_kind VARCHAR(64)")
            SchemaVersionsTable.insert { it[version] = 2 }
        }
        if (currentVersion < 3) {
            SchemaUtils.create(RagSourceChunksTable)
            SchemaVersionsTable.insert { it[version] = 3 }
        }
        if (currentVersion < 4) {
            SchemaUtils.create(TestCoveragePlansTable, TestCoveragePlanItemsTable)
            SchemaVersionsTable.insert { it[version] = 4 }
        }
    }
}

private object SchemaVersionsTable : Table("schema_versions") {
    val version = integer("version")
    override val primaryKey = PrimaryKey(version)
}

private object ProjectsTable : Table("projects") {
    val id = varchar("id", 64)
    val name = varchar("name", 256)
    val path = varchar("path", 2048).uniqueIndex()
    val scanStatus = varchar("scan_status", 32)
    val lastScannedAt = long("last_scanned_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

private object TasksTable : Table("tasks") {
    val id = varchar("id", 64)
    val projectId = varchar("project_id", 64).index()
    val scenario = varchar("scenario", 64)
    val mode = varchar("mode", 64)
    val status = varchar("status", 64)
    val author = varchar("author", 256)
    val input = text("input")
    val modelProfileId = varchar("model_profile_id", 64).nullable()
    val pendingApprovalKind = varchar("pending_approval_kind", 64).nullable()
    val idempotencyKey = varchar("idempotency_key", 256).nullable().uniqueIndex()
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")
    override val primaryKey = PrimaryKey(id)
}

private object TaskStepsTable : Table("task_steps") {
    val id = varchar("id", 64)
    val taskId = varchar("task_id", 64).index()
    val stage = varchar("stage", 128)
    val executor = varchar("executor", 128)
    val status = varchar("status", 64)
    val input = text("input")
    val result = text("result").nullable()
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")
    override val primaryKey = PrimaryKey(id)
}

private object TaskEventsTable : Table("task_events") {
    val id = varchar("id", 64)
    val taskId = varchar("task_id", 64).index()
    val occurredAt = long("occurred_at")
    val level = varchar("level", 16)
    val type = varchar("type", 128)
    val message = varchar("message", 1024)
    val payload = text("payload").nullable()
    override val primaryKey = PrimaryKey(id)
}

private object ArtifactsTable : Table("artifacts") {
    val id = varchar("id", 64)
    val taskId = varchar("task_id", 64).index()
    val type = varchar("type", 128)
    val path = varchar("path", 2048).nullable()
    val content = text("content").nullable()
    val sha256 = varchar("sha256", 128).nullable()
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(id)
}

private object ApprovalsTable : Table("approvals") {
    val id = varchar("id", 64)
    val taskId = varchar("task_id", 64).index()
    val kind = varchar("kind", 64)
    val decision = varchar("decision", 64)
    val author = varchar("author", 256)
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(id)
}

private object SourceDocumentsTable : Table("source_documents") {
    val projectId = varchar("project_id", 64).index()
    val path = varchar("path", 2048)
    val revision = varchar("revision", 128)
    val lineStart = integer("line_start")
    val lineEnd = integer("line_end")
    val sha256 = varchar("sha256", 128)
    val content = text("content")
    override val primaryKey = PrimaryKey(projectId, path)
}

/** Version 3 keeps independent line chunks while preserving existing v2 source_documents data. */
private object RagSourceChunksTable : Table("rag_source_chunks") {
    val projectId = varchar("project_id", 64).index()
    val path = varchar("path", 2048)
    val revision = varchar("revision", 128)
    val lineStart = integer("line_start")
    val lineEnd = integer("line_end")
    val sha256 = varchar("sha256", 128)
    val content = text("content")
    override val primaryKey = PrimaryKey(projectId, path, lineStart)
}

private object TestCoveragePlansTable : Table("test_coverage_plans") {
    val id = varchar("id", 64)
    val projectId = varchar("project_id", 64).uniqueIndex()
    val target = varchar("target", 64)
    val analyzedAt = long("analyzed_at")
    override val primaryKey = PrimaryKey(id)
}

private object TestCoveragePlanItemsTable : Table("test_coverage_plan_items") {
    val id = varchar("id", 64)
    val planId = varchar("plan_id", 64).index()
    val className = varchar("class_name", 512)
    val sourcePath = varchar("source_path", 2048)
    val testPath = varchar("test_path", 2048)
    val status = varchar("status", 64)
    val reason = text("reason").nullable()
    val taskId = varchar("task_id", 64).nullable()
    val branch = varchar("branch", 256).nullable()
    val pullRequestUrl = varchar("pull_request_url", 2048).nullable()
    val updatedAt = long("updated_at")
    override val primaryKey = PrimaryKey(id)
}

class ExposedProjectRepository(private val database: HarnessDatabase) : ProjectRepository {
    override suspend fun upsert(project: Project): Project = databaseQuery(database) {
        ProjectsTable.deleteWhere { ProjectsTable.id eq project.id }
        ProjectsTable.insert {
            it[id] = project.id
            it[name] = project.name
            it[path] = project.path
            it[scanStatus] = project.scanStatus.name
            it[lastScannedAt] = project.lastScannedAt
        }
        project
    }

    override suspend fun list(): List<Project> = databaseQuery(database) {
        ProjectsTable.selectAll().map(::toProject)
    }

    override suspend fun find(id: String): Project? = databaseQuery(database) {
        ProjectsTable.selectAll().where { ProjectsTable.id eq id }.singleOrNull()?.let(::toProject)
    }
}

class ExposedTaskRepository(private val database: HarnessDatabase) : TaskRepository {
    override suspend fun create(task: Task, idempotencyKey: String?): Task = databaseQuery(database) {
        TasksTable.insert {
            it[id] = task.id
            it[projectId] = task.projectId
            it[scenario] = task.scenario.name
            it[mode] = task.mode.name
            it[status] = task.status.name
            it[author] = task.author
            it[input] = task.input
            it[modelProfileId] = task.modelProfileId
            it[pendingApprovalKind] = task.pendingApprovalKind?.name
            it[TasksTable.idempotencyKey] = idempotencyKey
            it[createdAt] = task.createdAt
            it[updatedAt] = task.updatedAt
        }
        task
    }

    override suspend fun find(id: String): Task? = databaseQuery(database) {
        TasksTable.selectAll().where { TasksTable.id eq id }.singleOrNull()?.let(::toTask)
    }

    override suspend fun findByIdempotencyKey(key: String): Task? = databaseQuery(database) {
        TasksTable.selectAll().where { TasksTable.idempotencyKey eq key }.singleOrNull()?.let(::toTask)
    }

    override suspend fun list(): List<Task> = databaseQuery(database) {
        TasksTable.selectAll().map(::toTask).sortedByDescending(Task::createdAt)
    }

    override suspend fun updateStatus(id: String, status: TaskStatus, updatedAt: Long): Task = databaseQuery(database) {
        TasksTable.update({ TasksTable.id eq id }) {
            it[TasksTable.status] = status.name
            it[TasksTable.updatedAt] = updatedAt
        }
        requireNotNull(TasksTable.selectAll().where { TasksTable.id eq id }.singleOrNull()?.let(::toTask))
    }

    override suspend fun updatePendingApproval(id: String, kind: ApprovalKind?, updatedAt: Long): Task = databaseQuery(database) {
        TasksTable.update({ TasksTable.id eq id }) {
            it[pendingApprovalKind] = kind?.name
            it[TasksTable.updatedAt] = updatedAt
        }
        requireNotNull(TasksTable.selectAll().where { TasksTable.id eq id }.singleOrNull()?.let(::toTask))
    }

    override suspend fun addStep(step: TaskStep): TaskStep = databaseQuery(database) {
        TaskStepsTable.insert {
            it[id] = step.id
            it[taskId] = step.taskId
            it[stage] = step.stage
            it[executor] = step.executor
            it[status] = step.status.name
            it[input] = step.input
            it[result] = step.result
            it[createdAt] = step.createdAt
            it[updatedAt] = step.updatedAt
        }
        step
    }

    override suspend fun addEvent(event: TaskEvent): TaskEvent = databaseQuery(database) {
        TaskEventsTable.insert {
            it[id] = event.id
            it[taskId] = event.taskId
            it[occurredAt] = event.occurredAt
            it[level] = event.level.name
            it[type] = event.type
            it[message] = event.message
            it[payload] = event.payload
        }
        event
    }

    override suspend fun events(taskId: String): List<TaskEvent> = databaseQuery(database) {
        TaskEventsTable.selectAll().where { TaskEventsTable.taskId eq taskId }.map(::toEvent).sortedBy(TaskEvent::occurredAt)
    }

    override suspend fun addArtifact(artifact: Artifact): Artifact = databaseQuery(database) {
        ArtifactsTable.insert {
            it[id] = artifact.id
            it[taskId] = artifact.taskId
            it[type] = artifact.type
            it[path] = artifact.path
            it[content] = artifact.content
            it[sha256] = artifact.sha256
            it[createdAt] = artifact.createdAt
        }
        artifact
    }

    override suspend fun artifacts(taskId: String): List<Artifact> = databaseQuery(database) {
        ArtifactsTable.selectAll().where { ArtifactsTable.taskId eq taskId }.map(::toArtifact).sortedBy(Artifact::createdAt)
    }

    override suspend fun addApproval(approval: Approval): Approval = databaseQuery(database) {
        ApprovalsTable.insert {
            it[id] = approval.id
            it[taskId] = approval.taskId
            it[kind] = approval.kind.name
            it[decision] = approval.decision.name
            it[author] = approval.author
            it[createdAt] = approval.createdAt
        }
        approval
    }
}

class ExposedSourceRepository(private val database: HarnessDatabase) : SourceRepository {
    override suspend fun replaceForProject(projectId: String, documents: List<SourceDocument>) = databaseQuery(database) {
        RagSourceChunksTable.deleteWhere { RagSourceChunksTable.projectId eq projectId }
        documents.forEach { document ->
            RagSourceChunksTable.insert {
                it[RagSourceChunksTable.projectId] = document.projectId
                it[path] = document.path
                it[revision] = document.revision
                it[lineStart] = document.lineStart
                it[lineEnd] = document.lineEnd
                it[sha256] = document.sha256
                it[content] = document.content
            }
        }
    }

    override suspend fun search(projectId: String, query: String, limit: Int): List<SourceDocument> = databaseQuery(database) {
        val keywords = query.lowercase().split(Regex("\\s+")).filter { it.length >= 3 }.toSet()
        RagSourceChunksTable.selectAll()
            .where { RagSourceChunksTable.projectId eq projectId }
            .map(::toRagSource)
            .map { source -> source to keywords.count { it in source.content.lowercase() } }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
    }
}

class ExposedTestCoveragePlanRepository(private val database: HarnessDatabase) : TestCoveragePlanRepository {
    override suspend fun findByProject(projectId: String): TestCoveragePlan? = databaseQuery(database) {
        TestCoveragePlansTable.selectAll().where { TestCoveragePlansTable.projectId eq projectId }
            .singleOrNull()
            ?.let(::toCoveragePlan)
    }

    override suspend fun save(plan: TestCoveragePlan): TestCoveragePlan = databaseQuery(database) {
        TestCoveragePlansTable.deleteWhere { TestCoveragePlansTable.projectId eq plan.projectId }
        TestCoveragePlanItemsTable.deleteWhere { TestCoveragePlanItemsTable.planId eq plan.id }
        TestCoveragePlansTable.insert {
            it[id] = plan.id
            it[projectId] = plan.projectId
            it[target] = plan.target.name
            it[analyzedAt] = plan.analyzedAt
        }
        plan.items.forEach(::insertCoverageItem)
        plan
    }

    override suspend fun updateItem(item: TestCoveragePlanItem): TestCoveragePlanItem = databaseQuery(database) {
        TestCoveragePlanItemsTable.update({ TestCoveragePlanItemsTable.id eq item.id }) {
            it[className] = item.className
            it[sourcePath] = item.sourcePath
            it[testPath] = item.testPath
            it[status] = item.status.name
            it[reason] = item.reason
            it[taskId] = item.taskId
            it[branch] = item.branch
            it[pullRequestUrl] = item.pullRequestUrl
            it[updatedAt] = item.updatedAt
        }
        requireNotNull(TestCoveragePlanItemsTable.selectAll().where { TestCoveragePlanItemsTable.id eq item.id }
            .singleOrNull()?.let(::toCoverageItem))
    }

    private fun insertCoverageItem(item: TestCoveragePlanItem) {
        TestCoveragePlanItemsTable.insert {
            it[id] = item.id
            it[planId] = item.planId
            it[className] = item.className
            it[sourcePath] = item.sourcePath
            it[testPath] = item.testPath
            it[status] = item.status.name
            it[reason] = item.reason
            it[taskId] = item.taskId
            it[branch] = item.branch
            it[pullRequestUrl] = item.pullRequestUrl
            it[updatedAt] = item.updatedAt
        }
    }
}

private suspend fun <T> databaseQuery(database: HarnessDatabase, block: Transaction.() -> T): T =
    withContext(Dispatchers.IO) { transaction(database.database, statement = block) }

private fun toProject(row: org.jetbrains.exposed.v1.core.ResultRow) = Project(
    id = row[ProjectsTable.id], name = row[ProjectsTable.name], path = row[ProjectsTable.path],
    scanStatus = ProjectScanStatus.valueOf(row[ProjectsTable.scanStatus]), lastScannedAt = row[ProjectsTable.lastScannedAt],
)

private fun toTask(row: org.jetbrains.exposed.v1.core.ResultRow) = Task(
    id = row[TasksTable.id], projectId = row[TasksTable.projectId], scenario = TaskScenario.valueOf(row[TasksTable.scenario]),
    mode = TaskMode.valueOf(row[TasksTable.mode]), status = TaskStatus.valueOf(row[TasksTable.status]),
    author = row[TasksTable.author], input = row[TasksTable.input], createdAt = row[TasksTable.createdAt], updatedAt = row[TasksTable.updatedAt],
    modelProfileId = row[TasksTable.modelProfileId],
    pendingApprovalKind = row[TasksTable.pendingApprovalKind]?.let(ApprovalKind::valueOf),
)

private fun toEvent(row: org.jetbrains.exposed.v1.core.ResultRow) = TaskEvent(
    id = row[TaskEventsTable.id], taskId = row[TaskEventsTable.taskId], occurredAt = row[TaskEventsTable.occurredAt],
    level = EventLevel.valueOf(row[TaskEventsTable.level]), type = row[TaskEventsTable.type],
    message = row[TaskEventsTable.message], payload = row[TaskEventsTable.payload],
)

private fun toArtifact(row: org.jetbrains.exposed.v1.core.ResultRow) = Artifact(
    id = row[ArtifactsTable.id], taskId = row[ArtifactsTable.taskId], type = row[ArtifactsTable.type],
    path = row[ArtifactsTable.path], content = row[ArtifactsTable.content], sha256 = row[ArtifactsTable.sha256], createdAt = row[ArtifactsTable.createdAt],
)

private fun toRagSource(row: org.jetbrains.exposed.v1.core.ResultRow) = SourceDocument(
    projectId = row[RagSourceChunksTable.projectId], path = row[RagSourceChunksTable.path], revision = row[RagSourceChunksTable.revision],
    lineStart = row[RagSourceChunksTable.lineStart], lineEnd = row[RagSourceChunksTable.lineEnd], sha256 = row[RagSourceChunksTable.sha256], content = row[RagSourceChunksTable.content],
)

private fun toCoveragePlan(row: org.jetbrains.exposed.v1.core.ResultRow): TestCoveragePlan {
    val planId = row[TestCoveragePlansTable.id]
    val items = TestCoveragePlanItemsTable.selectAll().where { TestCoveragePlanItemsTable.planId eq planId }
        .map(::toCoverageItem)
        .sortedBy(TestCoveragePlanItem::className)
    return TestCoveragePlan(
        id = planId,
        projectId = row[TestCoveragePlansTable.projectId],
        target = TestCoverageTarget.valueOf(row[TestCoveragePlansTable.target]),
        analyzedAt = row[TestCoveragePlansTable.analyzedAt],
        items = items,
    )
}

private fun toCoverageItem(row: org.jetbrains.exposed.v1.core.ResultRow) = TestCoveragePlanItem(
    id = row[TestCoveragePlanItemsTable.id],
    planId = row[TestCoveragePlanItemsTable.planId],
    className = row[TestCoveragePlanItemsTable.className],
    sourcePath = row[TestCoveragePlanItemsTable.sourcePath],
    testPath = row[TestCoveragePlanItemsTable.testPath],
    status = TestCoverageStatus.valueOf(row[TestCoveragePlanItemsTable.status]),
    reason = row[TestCoveragePlanItemsTable.reason],
    taskId = row[TestCoveragePlanItemsTable.taskId],
    branch = row[TestCoveragePlanItemsTable.branch],
    pullRequestUrl = row[TestCoveragePlanItemsTable.pullRequestUrl],
    updatedAt = row[TestCoveragePlanItemsTable.updatedAt],
)
