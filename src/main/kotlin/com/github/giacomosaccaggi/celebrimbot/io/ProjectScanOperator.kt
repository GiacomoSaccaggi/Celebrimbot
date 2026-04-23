package com.github.giacomosaccaggi.celebrimbot.io

import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

interface ProjectScanOperator {
    fun listFiles(subPath: String? = null, extension: String? = null): String
    fun grepFiles(pattern: String, extension: String? = null): String
    fun findByName(name: String): String
    fun fileStats(target: String): String
}

class HeadlessProjectScanOperator(private val basePath: String) : ProjectScanOperator {

    override fun listFiles(subPath: String?, extension: String?): String {
        val root = if (subPath != null) File(basePath, subPath) else File(basePath)
        if (!root.exists()) return "Error: path not found: ${root.absolutePath}"

        return try {
            Files.walk(root.toPath())
                .filter { Files.isRegularFile(it) }
                .filter { extension == null || it.toString().endsWith(".$extension") }
                .filter { !it.toString().contains("/.git/") && !it.toString().contains("/build/") && !it.toString().contains("/node_modules/") }
                .map { Paths.get(basePath).relativize(it).toString() }
                .sorted()
                .toList()
                .joinToString("\n")
                .ifBlank { "No files found." }
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    override fun grepFiles(pattern: String, extension: String?): String {
        val regex = try { Regex(pattern, RegexOption.IGNORE_CASE) } catch (e: Exception) {
            return "Error: invalid regex pattern — ${e.message}"
        }

        val results = mutableListOf<String>()
        try {
            Files.walk(Paths.get(basePath))
                .filter { Files.isRegularFile(it) }
                .filter { extension == null || it.toString().endsWith(".$extension") }
                .filter { !it.toString().contains("/.git/") && !it.toString().contains("/build/") }
                .forEach { path ->
                    try {
                        val relPath = Paths.get(basePath).relativize(path).toString()
                        Files.readAllLines(path).forEachIndexed { index, line ->
                            if (regex.containsMatchIn(line)) {
                                results.add("$relPath:${index + 1}: $line")
                            }
                        }
                    } catch (_: Exception) {}
                }
        } catch (e: Exception) {
            return "Error: ${e.message}"
        }

        return if (results.isEmpty()) "No matches found for pattern: $pattern"
        else results.take(50).joinToString("\n") + if (results.size > 50) "\n... (${results.size - 50} more matches)" else ""
    }

    override fun findByName(name: String): String {
        return try {
            Files.walk(Paths.get(basePath))
                .filter { Files.isRegularFile(it) && it.fileName.toString().contains(name, ignoreCase = true) }
                .filter { !it.toString().contains("/.git/") && !it.toString().contains("/build/") }
                .map { Paths.get(basePath).relativize(it).toString() }
                .toList()
                .joinToString("\n")
                .ifBlank { "No files matching '$name' found." }
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    override fun fileStats(target: String): String {
        val file = File(basePath, target)
        if (!file.exists()) return "Error: file not found: $target"
        return try {
            val lines = file.readLines()
            val size = file.length()
            val blank = lines.count { it.isBlank() }
            "File: $target\nSize: ${size} bytes\nLines: ${lines.size}\nBlank lines: $blank\nCode lines: ${lines.size - blank}"
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }
}
