package com.harnessadvent.adapters

import com.harnessadvent.domain.SourceDocument
import java.nio.charset.StandardCharsets
import java.nio.file.Files
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
        require(Files.isDirectory(candidate)) { "Разрешённый каталог проекта недоступен." }
        return candidate
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
        val IGNORED_DIRECTORIES = setOf(".git", ".gradle", "build", "out", "node_modules", ".idea", "rag-index", "logs")
        val API_DIRECTORIES = setOf("api", "schemas", "schema", "contracts")
        val SOURCE_EXTENSIONS = setOf(
            "kt", "kts", "java", "ts", "tsx", "js", "jsx", "py", "go", "rs", "rb", "php", "cs", "swift",
            "c", "cc", "cpp", "h", "hpp", "sql", "xml", "html", "css", "scss",
        )
    }
}

private fun String.endsWithAny(vararg suffixes: String): Boolean = suffixes.any(::endsWith)
