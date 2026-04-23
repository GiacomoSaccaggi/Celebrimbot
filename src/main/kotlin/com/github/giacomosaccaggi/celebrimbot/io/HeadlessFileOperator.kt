package com.github.giacomosaccaggi.celebrimbot.io

import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.charset.StandardCharsets

/**
 * Standard I/O implementation of FileOperator for standalone CLI usage.
 * Uses java.nio and java.io.
 */
class HeadlessFileOperator(private val basePath: String) : FileOperator {

    override fun readFile(path: String): String {
        val file = File(basePath, path)
        return if (file.exists()) Files.readString(file.toPath(), StandardCharsets.UTF_8)
        else "Error: File not found: $path"
    }

    override fun writeFile(path: String, content: String): Boolean {
        return try {
            val file = File(basePath, path)
            file.parentFile?.mkdirs()
            Files.writeString(file.toPath(), content, StandardCharsets.UTF_8)
            true
        } catch (e: Exception) {
            false
        }
    }

    override fun deleteFile(path: String): Boolean {
        return try {
            File(basePath, path).delete()
        } catch (e: Exception) {
            false
        }
    }

    override fun resolvePath(fileName: String): String? {
        // Simple recursive search in the base path for standalone mode
        return Files.walk(Paths.get(basePath))
            .filter { !Files.isDirectory(it) && it.fileName.toString() == fileName }
            .findFirst()
            .map { Paths.get(basePath).relativize(it).toString() }
            .orElse(null)
    }

    override fun getProjectBasePath(): String = basePath
}
