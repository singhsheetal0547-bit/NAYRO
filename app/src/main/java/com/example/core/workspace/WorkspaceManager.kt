package com.example.core.workspace

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class WorkspaceFileItem(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val lastModified: Long
)

class WorkspaceManager(private val context: Context) {

    private val workspaceDir: File by lazy {
        val dir = File(context.filesDir, "nayro_workspace")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        dir
    }

    init {
        ensureDefaultArtifacts()
    }

    private fun ensureDefaultArtifacts() {
        val readme = File(workspaceDir, "README_WORKSPACE.txt")
        if (!readme.exists()) {
            readme.writeText(
                "NAYRO Autonomous Workspace\n" +
                "Created: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}\n" +
                "Use this folder for storing research reports, extracted code snippets, notes, and task outputs."
            )
        }
    }

    fun listWorkspaceFiles(subDir: String = ""): List<WorkspaceFileItem> {
        val target = if (subDir.isBlank()) workspaceDir else File(workspaceDir, subDir)
        if (!target.exists() || !target.isDirectory) return emptyList()

        return target.listFiles()?.map { file ->
            WorkspaceFileItem(
                name = file.name,
                path = file.relativeTo(workspaceDir).path,
                isDirectory = file.isDirectory,
                sizeBytes = file.length(),
                lastModified = file.lastModified()
            )
        }?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) ?: emptyList()
    }

    fun readWorkspaceFile(filename: String): String {
        val file = File(workspaceDir, filename)
        return if (file.exists() && file.isFile) {
            file.readText()
        } else {
            "Error: File '$filename' not found in workspace."
        }
    }

    fun writeWorkspaceFile(filename: String, content: String): String {
        return try {
            val file = File(workspaceDir, filename)
            file.parentFile?.mkdirs()
            file.writeText(content)
            "Successfully saved file '${file.name}' (${file.length()} bytes) in workspace."
        } catch (e: Exception) {
            "Error writing workspace file '$filename': ${e.message}"
        }
    }

    fun inspectCodeFile(filename: String): String {
        val file = File(workspaceDir, filename)
        if (!file.exists()) return "Error: Code file '$filename' does not exist."
        val text = file.readText()
        val lines = text.lines()
        val summary = StringBuilder()
        summary.append("File: ${file.name} (${lines.size} lines)\n")
        summary.append("Imports: ${lines.count { it.trim().startsWith("import") }}\n")
        summary.append("Sample Preview:\n")
        lines.take(15).forEachIndexed { i, line ->
            summary.append("${i + 1}: $line\n")
        }
        return summary.toString()
    }

    fun createReportArtifact(title: String, summaryContent: String): String {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val filename = "Report_${title.replace(Regex("[^a-zA-Z0-9]"), "_")}_$timestamp.txt"
        val fullContent = "=== NAYRO RESEARCH REPORT: $title ===\nDate: ${Date()}\n\n$summaryContent\n"
        return writeWorkspaceFile(filename, fullContent)
    }
}
