package com.harnessadvent.adapters

import com.harnessadvent.bootstrap.TestGenerationConfig
import com.harnessadvent.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Выполняет только фиксированные Git и Gradle операции в worktree внутри разрешённого проекта. */
class LocalTestGenerationWorkspace(private val config: TestGenerationConfig) : TestGenerationWorkspace {
    override suspend fun create(project: Project, taskId: String, branch: String, testPath: String): TestWorkspace = io {
        require(config.isConfigured) { "Генерация тестов не настроена." }
        require(testPath.matches(Regex(".+/src/[^/]+Test/.+Test\\.kt"))) { "Недопустимый путь unit-теста." }
        val root = Path.of(project.path).toAbsolutePath().normalize()
        val repository = repository(root)
        require(repository == config.allowedRepository) { "Git-репозиторий не разрешён для генерации тестов." }
        val worktree = root.resolve(".harness-worktrees").resolve(taskId).normalize()
        require(worktree.startsWith(root.resolve(".harness-worktrees"))) { "Недопустимый путь worktree." }
        Files.createDirectories(worktree.parent)
        command(root, listOf("git", "worktree", "add", "--detach", worktree.toString(), "HEAD")).requireSuccess()
        try {
            command(root, listOf("git", "-C", worktree.toString(), "checkout", "-b", branch)).requireSuccess()
            TestWorkspace(worktree.toString(), branch, testPath)
        } catch (error: Throwable) {
            command(root, listOf("git", "worktree", "remove", "--force", worktree.toString()))
            throw error
        }
    }

    override suspend fun writeTest(workspace: TestWorkspace, content: String) = io {
        require(content.length in 1..200_000) { "Содержимое теста пустое или превышает лимит." }
        val root = Path.of(workspace.root).toAbsolutePath().normalize()
        val target = root.resolve(workspace.testPath).normalize()
        require(target.startsWith(root) && target.toString().replace('\\', '/').contains("/src/")) { "Недопустимый путь теста." }
        var parent = requireNotNull(target.parent)
        Files.createDirectories(parent)
        while (parent != root) {
            require(!Files.isSymbolicLink(parent)) { "Символьные ссылки недоступны в worktree." }
            parent = requireNotNull(parent.parent)
        }
        val temporary = Files.createTempFile(target.parent, ".harness-test-", ".tmp")
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8)
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
        Unit
    }

    override suspend fun runChecks(
        workspace: TestWorkspace,
        onOutput: suspend (String) -> Unit,
    ): TestCheckResult {
        val builder = processBuilder(Path.of(workspace.root), config.checkCommand)
        val process = withContext(Dispatchers.IO) { builder.start() }
        val output = StringBuffer()
        val chunks = LinkedBlockingQueue<String>()
        val reader = Thread({
            process.inputStream.bufferedReader(StandardCharsets.UTF_8).use { stream ->
                val buffer = CharArray(MAX_EVENT_OUTPUT)
                while (true) {
                    val size = stream.read(buffer)
                    if (size < 0) break
                    val safeChunk = sanitizeCheckOutput(buffer.concatToString(0, size))
                    synchronized(output) {
                        output.appendKeepingTail(safeChunk)
                    }
                    chunks.offer(safeChunk)
                }
            }
        }, "harness-gradle-output").apply {
            isDaemon = true
            start()
        }
        var timedOut = false
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(config.checkTimeoutSeconds)
            while (process.isAlive && System.nanoTime() < deadline) {
                drainOutput(chunks, onOutput)
                delay(200)
            }
            timedOut = process.isAlive
            if (timedOut) {
                val message = "Gradle-проверка остановлена: превышен таймаут ${config.checkTimeoutSeconds} с.\n"
                synchronized(output) {
                    output.appendKeepingTail(message)
                }
                chunks.offer(message)
                terminate(process)
            }
        } finally {
            if (process.isAlive) terminate(process)
            withContext(Dispatchers.IO) { reader.join(READER_JOIN_TIMEOUT_MILLIS) }
            drainOutput(chunks, onOutput)
        }
        val exitCode = if (process.isAlive) -1 else process.exitValue()
        return TestCheckResult(!timedOut && exitCode == 0, output.toString(), timedOut)
    }

    override suspend fun commit(workspace: TestWorkspace, className: String) = io {
        val root = Path.of(workspace.root)
        command(root, listOf("git", "add", "--", workspace.testPath)).requireSuccess()
        command(root, listOf(
            "git", "-c", "user.name=Harness Advent", "-c", "user.email=harness-advent@localhost",
            "commit", "-m", "test: add unit tests for ${className.substringAfterLast('.')}"
        )).requireSuccess()
        Unit
    }

    override suspend fun push(workspace: TestWorkspace) = io {
        val root = Path.of(workspace.root)
        command(root, listOf("git", "push", "origin", workspace.branch)).requireSuccess()
        Unit
    }

    override suspend fun cleanup(workspace: TestWorkspace) = io {
        val root = Path.of(workspace.root).toAbsolutePath().normalize()
        val projectRoot = root.parent?.parent ?: return@io
        command(projectRoot, listOf("git", "worktree", "remove", "--force", root.toString()))
    }

    private fun repository(root: Path): String {
        val remote = command(root, listOf("git", "config", "--get", "remote.origin.url")).requireSuccess().output.trim()
        return Regex("(?:github\\.com[:/])([^/]+/[^/.]+)(?:\\.git)?$").find(remote)?.groupValues?.get(1)
            ?: throw IllegalArgumentException("Удалённый origin не является GitHub-репозиторием.")
    }

    private fun command(directory: Path, arguments: List<String>): CommandResult {
        val builder = processBuilder(directory, arguments)
        val process = builder.start()
        val output = process.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        return CommandResult(process.waitFor(), output.take(MAX_OUTPUT))
    }

    private fun processBuilder(directory: Path, arguments: List<String>): ProcessBuilder {
        val builder = ProcessBuilder(arguments).directory(directory.toFile()).redirectErrorStream(true)
        // Кэш находится в volume состояния Harness и не попадает в подключённый
        // проект. Android Gradle Plugin хранит служебные файлы в /tmp: volume,
        // созданный предыдущей версией образа, может не содержать новый home.
        builder.environment()["HOME"] = "/tmp"
        builder.environment()["GRADLE_USER_HOME"] = "/var/lib/harness/gradle"
        builder.environment()["ANDROID_USER_HOME"] = "/tmp/.android"
        val javaToolOptions = builder.environment()["JAVA_TOOL_OPTIONS"].orEmpty().trim()
        builder.environment()["JAVA_TOOL_OPTIONS"] = listOf(javaToolOptions, "-Duser.home=/tmp")
            .filter(String::isNotBlank)
            .joinToString(" ")
        builder.environment().remove("HARNESS_GITHUB_TEST_GENERATION_TOKEN")
        if (arguments.firstOrNull() == "git" && "push" in arguments) {
            val token = requireNotNull(config.githubToken) { "Не настроен токен для push ветки." }
            val basicAuth = Base64.getEncoder().encodeToString("x-access-token:$token".toByteArray(StandardCharsets.UTF_8))
            builder.environment()["GIT_CONFIG_COUNT"] = "1"
            builder.environment()["GIT_CONFIG_KEY_0"] = "http.https://github.com/.extraheader"
            builder.environment()["GIT_CONFIG_VALUE_0"] = "AUTHORIZATION: basic $basicAuth"
        }
        return builder
    }

    private suspend fun drainOutput(chunks: LinkedBlockingQueue<String>, onOutput: suspend (String) -> Unit) {
        while (true) onOutput(chunks.poll() ?: return)
    }

    private fun terminate(process: Process) {
        process.destroy()
        if (!process.waitFor(PROCESS_GRACE_PERIOD_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            process.waitFor(PROCESS_GRACE_PERIOD_SECONDS, TimeUnit.SECONDS)
        }
    }

    private fun sanitizeCheckOutput(value: String): String = value
        .replace(Regex("(?i)(api[_-]?key|token|secret|password)\\s*[:=]\\s*\\S+"), "$1=[redacted]")
        .replace(Regex("(?i)(authorization:\\s*basic\\s+)\\S+"), "$1[redacted]")
        .replace(Regex("https?://[^\\s/@:]+:[^\\s/@]+@"), "https://[redacted]@")

    /** Компилятор обычно пишет точную ошибку в конце подробного вывода Gradle. */
    private fun StringBuffer.appendKeepingTail(value: String) {
        if (value.length >= MAX_OUTPUT) {
            setLength(0)
            append(value.takeLast(MAX_OUTPUT))
            return
        }
        val overflow = length + value.length - MAX_OUTPUT
        if (overflow > 0) delete(0, overflow)
        append(value)
    }

    private fun CommandResult.requireSuccess(): CommandResult {
        require(exitCode == 0) { "Разрешённая операция Git не выполнена (код $exitCode): ${output.take(1_000)}" }
        return this
    }

    private data class CommandResult(val exitCode: Int, val output: String)
    private companion object {
        const val MAX_OUTPUT = 50_000
        const val MAX_EVENT_OUTPUT = 4_000
        const val PROCESS_GRACE_PERIOD_SECONDS = 5L
        const val READER_JOIN_TIMEOUT_MILLIS = 5_000L
    }
}

private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }
