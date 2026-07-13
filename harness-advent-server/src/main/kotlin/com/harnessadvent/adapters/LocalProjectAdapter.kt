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
                .filter { it.isRegularFile() && isAllowed(it, root) && it.fileSize() <= MAX_FILE_SIZE_BYTES }
                .mapNotNull { path -> readSource(projectId, root, path) }
                .toList()
        }

    private fun isAllowed(path: Path, root: Path): Boolean {
        val relative = root.relativize(path).toString().replace('\\', '/')
        val segments = relative.split('/')
        if (segments.any { it in IGNORED_DIRECTORIES }) return false
        val name = path.name.lowercase()
        return !name.startsWith(".env") &&
            !name.endsWith(".pem") && !name.endsWith(".key") && !name.endsWith(".p12") && !name.endsWith(".pfx") &&
            !name.endsWith(".jar") && !name.endsWith(".class") && !name.endsWith(".png") && !name.endsWith(".jpg") &&
            !name.endsWith(".jpeg") && !name.endsWith(".gif") && !name.endsWith(".pdf")
    }

    private fun readSource(projectId: String, root: Path, path: Path): SourceDocument? = runCatching {
        val bytes = Files.readAllBytes(path)
        if (bytes.any { it == 0.toByte() }) return null
        val content = bytes.toString(StandardCharsets.UTF_8)
        val relativePath = root.relativize(path).toString().replace('\\', '/')
        SourceDocument(
            projectId = projectId,
            path = relativePath,
            revision = sha256(bytes),
            lineStart = 1,
            lineEnd = content.lineSequence().count().coerceAtLeast(1),
            sha256 = sha256(bytes),
            content = content,
        )
    }.getOrNull()

    private fun sha256(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(value)
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val MAX_FILE_SIZE_BYTES = 1_000_000L
        val IGNORED_DIRECTORIES = setOf(".git", ".gradle", "build", "out", "node_modules", ".idea", "rag-index", "logs")
    }
}
