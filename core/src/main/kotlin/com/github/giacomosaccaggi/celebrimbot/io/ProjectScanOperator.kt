package com.github.giacomosaccaggi.celebrimbot.io

import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

interface ProjectScanOperator {
    fun listFiles(subPath: String? = null, extension: String? = null): String
    fun grepFiles(pattern: String, extension: String? = null): String
    fun findByName(name: String): String
    fun fileStats(target: String): String
    /**
     * Returns relative paths of all indexable source files under the project root.
     * Skips ignored directories, binary files, and files larger than 100 KB.
     * Used exclusively by the Palantír indexer.
     */
    fun walkSourceFiles(): List<String>
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
            "File: $target\nSize: $size bytes\nLines: ${lines.size}\nBlank lines: $blank\nCode lines: ${lines.size - blank}"
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    override fun walkSourceFiles(): List<String> {
        val base = Paths.get(basePath)
        return try {
            Files.walk(base)
                .filter { Files.isRegularFile(it) }
                .filter { path ->
                    val s = path.toString()
                    IGNORED_DIRS.none { s.contains(it) }
                }
                .filter { path ->
                    val ext = path.fileName.toString().substringAfterLast('.', "")
                    SOURCE_EXTENSIONS.contains(ext)
                }
                .filter { Files.size(it) <= MAX_FILE_BYTES }
                .filter { !isBinary(it.toFile()) }
                .map { base.relativize(it).toString() }
                .sorted()
                .toList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    companion object {
        private const val MAX_FILE_BYTES = 102_400L  // 100 KB
        private val IGNORED_DIRS = listOf(
            "/.git/", "/build/", "/node_modules/", "/.gradle/", "/.idea/",
            "/dist/", "/out/", "/.celebrimbot/"
        )
        private val SOURCE_EXTENSIONS = setOf(
            "kt", "java", "py", "js", "ts", "tsx", "jsx",
            "rs", "go", "c", "cpp", "cc", "h", "hpp",
            "cs", "rb", "scala", "swift", "php", "kts"
        )
        /** Detects binary files by looking for a null byte in the first 512 bytes. */
        private fun isBinary(file: File): Boolean = try {
            file.inputStream().use { stream ->
                val buf = ByteArray(512)
                val read = stream.read(buf)
                (0 until read).any { buf[it] == 0.toByte() }
            }
        } catch (_: Exception) { false }
    }
}
