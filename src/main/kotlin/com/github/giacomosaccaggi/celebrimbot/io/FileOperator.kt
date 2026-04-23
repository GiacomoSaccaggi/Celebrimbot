package com.github.giacomosaccaggi.celebrimbot.io

/**
 * Interface for file operations, allowing Celebrimbot to run either
 * inside IntelliJ (using PSI/VFS) or standalone (using java.nio).
 */
interface FileOperator {
    fun readFile(path: String): String
    fun writeFile(path: String, content: String): Boolean
    fun deleteFile(path: String): Boolean
    fun resolvePath(fileName: String): String?
    fun getProjectBasePath(): String?
}
