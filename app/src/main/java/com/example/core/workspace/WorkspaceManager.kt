package com.example.core.workspace

import android.content.Context
import java.io.File
import java.nio.file.Files

data class WorkspaceFileItem(
    val name: String,
    val absolutePath: String,
    val sizeBytes: Long,
    val isDirectory: Boolean,
    val lastModified: Long,
    val extension: String,
    val path: String = absolutePath
)

/**
 * Structured result for all workspace file operations (BUG 07 & BUG 08).
 * Prevents string-error masking where failed file reads/writes were treated as success.
 */
sealed class WorkspaceResult {
    abstract val isSuccess: Boolean
    abstract val displayMessage: String

    data class Success(
        val content: String,
        val path: String = "",
        val bytesProcessed: Long = 0L
    ) : WorkspaceResult() {
        override val isSuccess: Boolean = true
        override val displayMessage: String = content
    }

    data class Failure(val message: String) : WorkspaceResult() {
        override val isSuccess: Boolean = false
        override val displayMessage: String = message
    }

    data class NotFound(val message: String) : WorkspaceResult() {
        override val isSuccess: Boolean = false
        override val displayMessage: String = message
    }

    data class PermissionDenied(val message: String) : WorkspaceResult() {
        override val isSuccess: Boolean = false
        override val displayMessage: String = message
    }

    data class InvalidPath(val message: String) : WorkspaceResult() {
        override val isSuccess: Boolean = false
        override val displayMessage: String = message
    }

    data class SizeLimitExceeded(val message: String) : WorkspaceResult() {
        override val isSuccess: Boolean = false
        override val displayMessage: String = message
    }
}

/**
 * Persistent Workspace & File System Manager for NAYRO.
 * Enforces canonical path containment, symlink escape prevention, filename validation,
 * and file size limits inside the sandboxed `nayro_workspace` directory.
 */
class WorkspaceManager(private val workspaceDir: File) {

    constructor(context: Context) : this(File(context.filesDir, "nayro_workspace"))

    companion object {
        const val MAX_FILE_SIZE_BYTES: Long = 5L * 1024L * 1024L // 5 MB limit
        private val INVALID_FILENAME_CHARS = charArrayOf('\u0000', ':', '*', '?', '"', '<', '>', '|')
    }

    private val canonicalRoot: File

    init {
        if (!workspaceDir.exists()) {
            workspaceDir.mkdirs()
        }
        canonicalRoot = workspaceDir.canonicalFile
        seedDefaultWorkspaceArtifacts()
    }

    private fun seedDefaultWorkspaceArtifacts() {
        val welcomeDoc = File(canonicalRoot, "NAYRO_Workspace_Guide.md")
        if (!welcomeDoc.exists()) {
            welcomeDoc.writeText(
                """
                # NAYRO Autonomous Workspace
                Welcome to the NAYRO persistent agent workspace.
                
                ## Capabilities
                - **Document Analysis**: Read, summarize, and extract key insights from Markdown, TXT, JSON, and code files.
                - **Coding Agent**: Inspect source files, diagnose errors, and generate clean code patches.
                - **Research Reports**: Save synthesized multi-source web research directly into structured reports.
                """.trimIndent()
            )
        }

        val sampleCode = File(canonicalRoot, "SampleAgentModule.kt")
        if (!sampleCode.exists()) {
            sampleCode.writeText(
                """
                package com.nayro.sample
                
                class SampleAgentModule {
                    fun computeEfficiency(tasksCompleted: Int, totalTimeMs: Long): Double {
                        if (totalTimeMs <= 0) return 0.0
                        return (tasksCompleted.toDouble() / totalTimeMs) * 1000.0
                    }
                }
                """.trimIndent()
            )
        }
    }

    /**
     * Validates a relative workspace path and resolves it strictly within [canonicalRoot].
     * Rejects path traversal (`../`, `..\`), absolute paths, null bytes, and symlink escapes.
     */
    fun resolveSafeFile(relativePath: String): Result<File> {
        val raw = relativePath.trim()
        if (raw.isEmpty()) {
            return Result.failure(IllegalArgumentException("File path cannot be empty."))
        }
        if (raw.any { it in INVALID_FILENAME_CHARS }) {
            return Result.failure(IllegalArgumentException("File path contains illegal characters: '$raw'."))
        }
        if (raw.startsWith("/") || raw.startsWith("\\") || raw.matches(Regex("^[a-zA-Z]:.*"))) {
            return Result.failure(SecurityException("Absolute paths are forbidden outside workspace: '$raw'."))
        }

        val normalizedSlashes = raw.replace('\\', '/')
        val segments = normalizedSlashes.split('/')
        if (segments.any { it == ".." || it == "." || it.isBlank() }) {
            return Result.failure(SecurityException("Path traversal or invalid relative segment rejected: '$raw'."))
        }

        val candidate = File(canonicalRoot, normalizedSlashes)
        val canonicalCandidate = try {
            candidate.canonicalFile
        } catch (e: Exception) {
            return Result.failure(SecurityException("Failed to resolve canonical path for '$raw'."))
        }

        val rootPathWithSep = canonicalRoot.path + File.separator
        if (canonicalCandidate != canonicalRoot && !canonicalCandidate.path.startsWith(rootPathWithSep)) {
            return Result.failure(SecurityException("Path escapes workspace root containment: '$raw'."))
        }

        // Check symlink escape on existing file or parent directories using API 24-safe canonicalFile
        try {
            var check: File? = candidate
            while (check != null && check.absolutePath.startsWith(canonicalRoot.path)) {
                if (check.exists()) {
                    val realTarget = check.canonicalFile
                    if (realTarget != canonicalRoot && !realTarget.path.startsWith(rootPathWithSep)) {
                        return Result.failure(SecurityException("Symlink escape outside workspace rejected: '$raw'."))
                    }
                }
                if (check == canonicalRoot) break
                check = check.parentFile
            }
        } catch (e: Exception) {
            return Result.failure(SecurityException("Symlink verification failed for '$raw': ${e.message}"))
        }

        return Result.success(canonicalCandidate)
    }

    fun listWorkspaceFiles(): List<WorkspaceFileItem> {
        val files = canonicalRoot.listFiles() ?: return emptyList()
        return files
            .filter { file ->
                try {
                    val canonical = file.canonicalFile
                    canonical.path.startsWith(canonicalRoot.path + File.separator)
                } catch (_: Exception) {
                    false
                }
            }
            .map { file ->
                WorkspaceFileItem(
                    name = file.name,
                    absolutePath = file.absolutePath,
                    sizeBytes = file.length(),
                    isDirectory = file.isDirectory,
                    lastModified = file.lastModified(),
                    extension = file.extension.lowercase()
                )
            }
            .sortedByDescending { it.lastModified }
    }

    fun readFileResult(filename: String): WorkspaceResult {
        val resolved = resolveSafeFile(filename)
        val target = resolved.getOrElse { err ->
            return WorkspaceResult.InvalidPath(err.message ?: "Invalid workspace path: '$filename'")
        }

        if (!target.exists() || !target.isFile) {
            return WorkspaceResult.NotFound("File '$filename' not found in workspace.")
        }
        if (!target.canRead()) {
            return WorkspaceResult.PermissionDenied("Permission denied reading file '$filename'.")
        }
        if (target.length() > MAX_FILE_SIZE_BYTES) {
            return WorkspaceResult.SizeLimitExceeded(
                "File '$filename' (${target.length()} bytes) exceeds maximum limit ($MAX_FILE_SIZE_BYTES bytes)."
            )
        }

        return try {
            val content = target.readText()
            WorkspaceResult.Success(
                content = content,
                path = target.name,
                bytesProcessed = target.length()
            )
        } catch (e: SecurityException) {
            WorkspaceResult.PermissionDenied("Permission denied reading '$filename': ${e.message}")
        } catch (e: Exception) {
            WorkspaceResult.Failure("Error reading file '$filename': ${e.message}")
        }
    }

    fun writeFileResult(filename: String, content: String): WorkspaceResult {
        val resolved = resolveSafeFile(filename)
        val target = resolved.getOrElse { err ->
            return WorkspaceResult.InvalidPath(err.message ?: "Invalid workspace path: '$filename'")
        }

        val byteSize = content.toByteArray(Charsets.UTF_8).size.toLong()
        if (byteSize > MAX_FILE_SIZE_BYTES) {
            return WorkspaceResult.SizeLimitExceeded(
                "Content size ($byteSize bytes) exceeds maximum allowed limit ($MAX_FILE_SIZE_BYTES bytes)."
            )
        }

        return try {
            target.parentFile?.let { parent ->
                if (!parent.exists()) parent.mkdirs()
            }
            target.writeText(content, Charsets.UTF_8)
            if (!target.exists() || !target.isFile) {
                return WorkspaceResult.Failure("Failed to verify written file '$filename' on disk.")
            }
            WorkspaceResult.Success(
                content = "Saved '$filename' (${target.length()} bytes) in NAYRO Workspace.",
                path = target.name,
                bytesProcessed = target.length()
            )
        } catch (e: SecurityException) {
            WorkspaceResult.PermissionDenied("Permission denied writing '$filename': ${e.message}")
        } catch (e: Exception) {
            WorkspaceResult.Failure("Failed to write file '$filename': ${e.message}")
        }
    }

    fun searchFilesResult(query: String): WorkspaceResult {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            return WorkspaceResult.Failure("Search query cannot be empty.")
        }
        val matches = mutableListOf<String>()
        for (item in listWorkspaceFiles()) {
            if (item.isDirectory) continue
            val nameMatch = item.name.contains(trimmed, ignoreCase = true)
            val contentResult = readFileResult(item.name)
            val contentMatch = contentResult is WorkspaceResult.Success &&
                contentResult.content.contains(trimmed, ignoreCase = true)
            if (nameMatch || contentMatch) {
                matches.add("${item.name} (${item.sizeBytes} bytes)")
            }
        }
        return if (matches.isEmpty()) {
            WorkspaceResult.NotFound("No workspace files matched query '$trimmed'.")
        } else {
            WorkspaceResult.Success(
                content = "Found ${matches.size} matching file(s) for '$trimmed': ${matches.joinToString(", ")}",
                bytesProcessed = matches.size.toLong()
            )
        }
    }

    fun inspectCodeFileResult(filename: String): WorkspaceResult {
        val readRes = readFileResult(filename)
        if (readRes !is WorkspaceResult.Success) return readRes

        val content = readRes.content
        val lines = content.lines()
        val imports = lines.count { it.trim().startsWith("import ") }
        val classes = lines.count { it.contains("class ") || it.contains("object ") || it.contains("interface ") }
        val functions = lines.count { it.contains("fun ") }
        val openBraces = content.count { it == '{' }
        val closeBraces = content.count { it == '}' }
        val braceStatus = if (openBraces == closeBraces) "Balanced ($openBraces pairs)" else "Unbalanced (open=$openBraces, close=$closeBraces)"

        val report = "Code Inspection for '$filename': ${lines.size} lines, $imports imports, $classes types, $functions functions. Braces: $braceStatus.\n\n$content"
        return WorkspaceResult.Success(
            content = report,
            path = filename,
            bytesProcessed = readRes.bytesProcessed
        )
    }

    fun applyCodeEditResult(
        filename: String,
        patchContent: String,
        targetSnippet: String? = null
    ): WorkspaceResult {
        if (patchContent.isBlank()) {
            return WorkspaceResult.Failure("Code edit patch content cannot be empty.")
        }
        val existingRes = readFileResult(filename)
        val beforeContent = when (existingRes) {
            is WorkspaceResult.Success -> existingRes.content
            is WorkspaceResult.NotFound -> ""
            else -> return existingRes
        }

        val updatedContent = if (!targetSnippet.isNullOrBlank()) {
            if (!beforeContent.contains(targetSnippet)) {
                return WorkspaceResult.Failure("Target snippet not found in '$filename'; edit aborted.")
            }
            beforeContent.replaceFirst(targetSnippet, patchContent)
        } else {
            patchContent
        }

        val writeRes = writeFileResult(filename, updatedContent)
        if (writeRes !is WorkspaceResult.Success) return writeRes

        val beforeLines = beforeContent.lines().size
        val afterLines = updatedContent.lines().size
        val delta = afterLines - beforeLines
        val deltaStr = if (delta >= 0) "+$delta" else "$delta"

        return WorkspaceResult.Success(
            content = "Updated '$filename' (${writeRes.bytesProcessed} bytes, line delta: $deltaStr lines).",
            path = filename,
            bytesProcessed = writeRes.bytesProcessed
        )
    }

    fun diagnoseCodeErrorResult(filename: String, errorContext: String? = null): WorkspaceResult {
        val readRes = readFileResult(filename)
        if (readRes !is WorkspaceResult.Success) return readRes

        val content = readRes.content
        val lines = content.lines()
        val findings = mutableListOf<String>()

        val openBraces = content.count { it == '{' }
        val closeBraces = content.count { it == '}' }
        if (openBraces != closeBraces) {
            findings.add("Unbalanced curly braces: $openBraces '{' vs $closeBraces '}'.")
        }

        val openParens = content.count { it == '(' }
        val closeParens = content.count { it == ')' }
        if (openParens != closeParens) {
            findings.add("Unbalanced parentheses: $openParens '(' vs $closeParens ')'.")
        }

        lines.forEachIndexed { idx, line ->
            if (line.contains("!!") && !line.trim().startsWith("//")) {
                findings.add("Line ${idx + 1}: Non-null assertion operator (!!) may throw NullPointerException.")
            }
            if (line.contains("TODO(") || line.contains("FIXME")) {
                findings.add("Line ${idx + 1}: Unresolved TODO/FIXME marker.")
            }
        }

        if (!errorContext.isNullOrBlank()) {
            findings.add("Reported diagnostic context: $errorContext")
        }

        val summary = if (findings.isEmpty()) {
            "Diagnosed '$filename' (${lines.size} lines): No structural syntax or brace errors detected."
        } else {
            "Diagnosed '$filename' (${lines.size} lines) — ${findings.size} issue(s) found:\n" +
                findings.joinToString("\n") { "- $it" }
        }

        return WorkspaceResult.Success(
            content = summary,
            path = filename,
            bytesProcessed = readRes.bytesProcessed
        )
    }

    fun generateCodeDocsResult(filename: String): WorkspaceResult {
        val readRes = readFileResult(filename)
        if (readRes !is WorkspaceResult.Success) return readRes

        val lines = readRes.content.lines()
        val packageLine = lines.firstOrNull { it.trim().startsWith("package ") }?.removePrefix("package ")?.trim() ?: "default"
        val declarations = lines.mapNotNull { line ->
            val t = line.trim()
            when {
                t.startsWith("class ") || t.startsWith("data class ") || t.startsWith("sealed class ") || t.startsWith("object ") || t.startsWith("interface ") ->
                    "Type: ${t.substringBefore("{").trim()}"
                t.startsWith("fun ") || t.startsWith("suspend fun ") ->
                    "Function: ${t.substringBefore("{").trim()}"
                else -> null
            }
        }

        val docContent = buildString {
            appendLine("# Documentation for `$filename`")
            appendLine("- **Package**: `$packageLine`")
            appendLine("- **Total Lines**: ${lines.size}")
            appendLine("## Declarations")
            if (declarations.isEmpty()) {
                appendLine("- No top-level declarations found.")
            } else {
                declarations.forEach { appendLine("- `$it`") }
            }
        }.trim()

        return WorkspaceResult.Success(
            content = docContent,
            path = filename,
            bytesProcessed = readRes.bytesProcessed
        )
    }

    // Legacy string-returning wrappers preserved for UI screens
    fun readWorkspaceFile(filename: String): String {
        return readFileResult(filename).displayMessage
    }

    fun writeWorkspaceFile(filename: String, content: String): String {
        return writeFileResult(filename, content).displayMessage
    }

    fun inspectCodeFile(filename: String): String {
        return inspectCodeFileResult(filename).displayMessage
    }

    fun createReportArtifact(topic: String, synthesizedContent: String): String {
        val sanitized = topic.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(30).ifBlank { "Report" }
        val filename = "Research_${sanitized}.md"
        return writeWorkspaceFile(filename, synthesizedContent)
    }
}
