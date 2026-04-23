package com.github.giacomosaccaggi.celebrimbot.io

/**
 * Interface for LLM inference engines.
 */
interface LlmEngine {
    fun askQuestion(prompt: String, stopStrings: List<String>): String
    fun isModelDownloaded(): Boolean
    fun downloadModel(): java.util.concurrent.CompletableFuture<Boolean>
}
