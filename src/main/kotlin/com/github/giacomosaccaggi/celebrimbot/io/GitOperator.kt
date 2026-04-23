package com.github.giacomosaccaggi.celebrimbot.io

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

interface GitOperator {
    fun status(): String
    fun log(maxEntries: Int = 10): String
    fun diff(target: String? = null): String
    fun blame(target: String): String
    fun branch(): String
}

class HeadlessGitOperator(private val basePath: String) : GitOperator {

    private fun run(vararg args: String): String {
        return try {
            val process = ProcessBuilder("git", *args)
                .directory(File(basePath))
                .redirectErrorStream(true)
                .start()
            val output = BufferedReader(InputStreamReader(process.inputStream)).readText()
            process.waitFor(15, TimeUnit.SECONDS)
            output.trim().ifBlank { "(no output)" }
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    override fun status(): String = run("status", "--short")

    override fun log(maxEntries: Int): String =
        run("log", "--oneline", "--decorate", "-$maxEntries")

    override fun diff(target: String?): String {
        val result = if (target != null) run("diff", "HEAD", "--", target) else run("diff", "HEAD")
        return result.take(6000).ifBlank { "No changes." }
    }

    override fun blame(target: String): String =
        run("blame", "--line-porcelain", target).take(4000)

    override fun branch(): String = run("branch", "--show-current")
}
