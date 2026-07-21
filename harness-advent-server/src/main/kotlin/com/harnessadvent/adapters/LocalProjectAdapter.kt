package com.harnessadvent.adapters

import com.harnessadvent.domain.SourceDocument
import com.harnessadvent.domain.FileSearchMatch
import com.harnessadvent.domain.FileWriteResult
import com.harnessadvent.domain.Project
import com.harnessadvent.domain.ProjectFiles
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.StandardCopyOption
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.absolutePathString
import kotlin.io.path.fileSize
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.streams.asSequence

class AllowedProjectPolicy(private val allowedPaths: Set<Path>) {
    fun validate(path: String): Path {
        val candidate = Path.of(path).toAbsolutePath().normalize()
        require(candidate in allowedPaths) { "Каталог проекта не разрешён конфигурацией сервера." }
        require(!Files.isSymbolicLink(candidate)) { "Символьная ссылка не может быть каталогом проекта." }
        require(Files.isDirectory(candidate)) { "Разрешённый каталог проекта недоступен." }
        return candidate
    }
}

/**
 * Единственная файловая граница для агентского сценария. Путь всегда
 * интерпретируется относительно зарегистрированного проекта; shell-команды
 * здесь намеренно не используются.
 */
class SafeProjectFiles(private val allowedProjectPolicy: AllowedProjectPolicy) : ProjectFiles {
    override suspend fun list(project: Project, limit: Int): List<String> = files(project)
        .take(limit.coerceIn(1, MAX_LIST_FILES))
        .map { root -> currentRoot(project).relativize(root).toString().replace('\\', '/') }
        .toList()

    override suspend fun search(project: Project, query: String, limit: Int): List<FileSearchMatch> {
        require(query.isNotBlank() && query.length <= MAX_QUERY_LENGTH) { "Поисковый запрос некорректен." }
        val safeLimit = limit.coerceIn(1, MAX_SEARCH_MATCHES)
        val root = currentRoot(project)
        return files(project).flatMap { file ->
            Files.readAllLines(file, StandardCharsets.UTF_8).asSequence().mapIndexedNotNull { index, line ->
                if (line.contains(query, ignoreCase = true)) {
                    FileSearchMatch(root.relativize(file).toString().replace('\\', '/'), index + 1, line.take(MAX_MATCH_LINE_LENGTH))
                } else null
            }
        }.take(safeLimit).toList()
    }

    override suspend fun read(project: Project, path: String): String {
        val file = resolve(project, path, requireExisting = true)
        require(Files.isRegularFile(file)) { "Можно читать только обычные файлы." }
        require(Files.size(file) <= MAX_FILE_SIZE_BYTES) { "Файл превышает лимит чтения." }
        val bytes = Files.readAllBytes(file)
        require(bytes.none { it == 0.toByte() }) { "Бинарные файлы недоступны агенту." }
        return bytes.toString(StandardCharsets.UTF_8)
    }

    override suspend fun write(project: Project, path: String, content: String): FileWriteResult {
        require(content.length <= MAX_FILE_SIZE_BYTES) { "Содержимое превышает лимит записи." }
        val file = resolve(project, path, requireExisting = false)
        val parent = requireNotNull(file.parent) { "Для файла требуется родительский каталог." }
        Files.createDirectories(parent)
        validatePathWithoutLinks(currentRoot(project), file)
        val previous = if (Files.exists(file)) {
            require(Files.isRegularFile(file)) { "Можно изменять только обычные файлы." }
            require(Files.size(file) <= MAX_FILE_SIZE_BYTES) { "Файл превышает лимит записи." }
            val bytes = Files.readAllBytes(file)
            require(bytes.none { it == 0.toByte() }) { "Бинарные файлы недоступны агенту." }
            bytes.toString(StandardCharsets.UTF_8)
        } else null
        val temporary = Files.createTempFile(parent, ".harness-", ".tmp")
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8)
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
        return FileWriteResult(path, previous, content)
    }

    private fun files(project: Project): Sequence<Path> {
        val root = currentRoot(project)
        return Files.walk(root, MAX_DEPTH).use { paths ->
            paths.asSequence()
                .filter { !Files.isSymbolicLink(it) && it.isRegularFile() && isAllowedFile(root, it) && it.fileSize() <= MAX_FILE_SIZE_BYTES }
                .toList()
                .asSequence()
        }
    }

    private fun currentRoot(project: Project): Path = allowedProjectPolicy.validate(project.path)

    private fun resolve(project: Project, rawPath: String, requireExisting: Boolean): Path {
        require(rawPath.isSafeRelativePath()) { "Недопустимый путь файла." }
        val root = currentRoot(project)
        val result = root.resolve(rawPath).normalize()
        require(result.startsWith(root)) { "Путь выходит за пределы проекта." }
        require(isAllowedFile(root, result)) { "Этот путь недоступен агенту." }
        if (requireExisting) require(Files.exists(result)) { "Файл не найден." }
        validatePathWithoutLinks(root, result)
        return result
    }

    private fun validatePathWithoutLinks(root: Path, path: Path) {
        var current = root
        for (segment in root.relativize(path)) {
            current = current.resolve(segment)
            require(!Files.isSymbolicLink(current)) { "Символьные ссылки недоступны агенту." }
        }
    }

    private fun isAllowedFile(root: Path, path: Path): Boolean {
        val relative = root.relativize(path).toString().replace('\\', '/')
        val segments = relative.split('/').map(String::lowercase)
        val name = path.fileName?.toString()?.lowercase().orEmpty()
        val extension = name.substringAfterLast('.', missingDelimiterValue = "")
        val isRootDocumentation = segments.size == 1 && (name.startsWith("readme") || name.startsWith("changelog") || name.startsWith("adr"))
        val isDocumentation = segments.firstOrNull() == "docs" || extension == "md"
        val isCode = extension in SOURCE_EXTENSIONS
        val isApiDefinition = segments.any { it in API_DIRECTORIES } && extension in setOf("md", "yaml", "yml", "json", "toml")
        return segments.none { it in BLOCKED_DIRECTORIES || it.startsWith(".env") } &&
            !name.startsWith(".env") &&
            !name.endsWithAny(".pem", ".key", ".p12", ".pfx", ".jar", ".class", ".png", ".jpg", ".jpeg", ".gif", ".pdf") &&
            (isRootDocumentation || isDocumentation || isCode || isApiDefinition)
    }

    private fun String.isSafeRelativePath(): Boolean =
        isNotBlank() && length <= MAX_PATH_LENGTH && !startsWith('/') && !contains('\\') &&
            split('/').none { it.isBlank() || it == "." || it == ".." }

    private companion object {
        const val MAX_DEPTH = 16
        const val MAX_LIST_FILES = 1_000
        const val MAX_SEARCH_MATCHES = 200
        const val MAX_QUERY_LENGTH = 500
        const val MAX_MATCH_LINE_LENGTH = 1_000
        const val MAX_FILE_SIZE_BYTES = 1_000_000
        const val MAX_PATH_LENGTH = 1_024
        val BLOCKED_DIRECTORIES = setOf(".git", ".gradle", "build", "out", "node_modules", ".idea", "rag-index", "logs", ".harness-worktrees")
        val API_DIRECTORIES = setOf("api", "schemas", "schema", "contracts")
        val SOURCE_EXTENSIONS = setOf(
            "kt", "kts", "java", "ts", "tsx", "js", "jsx", "py", "go", "rs", "rb", "php", "cs", "swift",
            "c", "cc", "cpp", "h", "hpp", "sql", "xml", "html", "css", "scss",
        )
    }
}

class SafeProjectScanner {
    fun scan(projectId: String, root: Path): List<SourceDocument> =
        Files.walk(root, 16).use { paths ->
            paths.asSequence()
                .filter { it.isRegularFile() && isIndexableSource(it, root) && it.fileSize() <= MAX_FILE_SIZE_BYTES }
                .flatMap { path -> readSources(projectId, root, path).asSequence() }
                .toList()
        }

    private fun isIndexableSource(path: Path, root: Path): Boolean {
        val relative = root.relativize(path).toString().replace('\\', '/')
        val segments = relative.split('/')
        if (segments.any { it in IGNORED_DIRECTORIES }) return false
        val name = path.name.lowercase()
        if (name.startsWith(".env") || name.endsWithAny(
                ".pem", ".key", ".p12", ".pfx", ".jar", ".class", ".png", ".jpg", ".jpeg", ".gif", ".pdf",
            )
        ) return false
        val isRootReadme = segments.size == 1 && name.startsWith("readme")
        val isDocumentation = segments.firstOrNull() == "docs"
        val isApiDescription = name.startsWith("openapi") || name.startsWith("swagger") ||
            (segments.any { it in API_DIRECTORIES } && name.endsWithAny(".md", ".yaml", ".yml", ".json"))
        val isCode = path.name.substringAfterLast('.', missingDelimiterValue = "").lowercase() in SOURCE_EXTENSIONS
        return isRootReadme || isDocumentation || isApiDescription || isCode
    }

    private fun readSources(projectId: String, root: Path, path: Path): List<SourceDocument> = runCatching {
        val bytes = Files.readAllBytes(path)
        if (bytes.any { it == 0.toByte() }) return emptyList()
        val content = bytes.toString(StandardCharsets.UTF_8)
        val relativePath = root.relativize(path).toString().replace('\\', '/')
        content.lineSequence().toList().chunked(CHUNK_LINES).mapIndexed { index, lines ->
            SourceDocument(
                projectId = projectId,
                path = relativePath,
                revision = sha256(bytes),
                lineStart = index * CHUNK_LINES + 1,
                lineEnd = index * CHUNK_LINES + lines.size,
                sha256 = sha256(lines.joinToString("\n").toByteArray(StandardCharsets.UTF_8)),
                content = lines.joinToString("\n"),
            )
        }
    }.getOrDefault(emptyList())

    private fun sha256(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(value)
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val MAX_FILE_SIZE_BYTES = 1_000_000L
        const val CHUNK_LINES = 80
        val IGNORED_DIRECTORIES = setOf(".git", ".gradle", "build", "out", "node_modules", ".idea", "rag-index", "logs", ".harness-worktrees")
        val API_DIRECTORIES = setOf("api", "schemas", "schema", "contracts")
        val SOURCE_EXTENSIONS = setOf(
            "kt", "kts", "java", "ts", "tsx", "js", "jsx", "py", "go", "rs", "rb", "php", "cs", "swift",
            "c", "cc", "cpp", "h", "hpp", "sql", "xml", "html", "css", "scss",
        )
    }
}

private fun String.endsWithAny(vararg suffixes: String): Boolean = suffixes.any(::endsWith)
