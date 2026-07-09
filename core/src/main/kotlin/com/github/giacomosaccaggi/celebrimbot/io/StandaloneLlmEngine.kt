package com.github.giacomosaccaggi.celebrimbot.io

import com.github.giacomosaccaggi.celebrimbot.engine.LazyModelManager
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.util.concurrent.CompletableFuture

/**
 * Standalone implementation of LlmEngine using java-llama.cpp via LazyModelManager.
 * No IntelliJ dependencies.
 *
 * The model is loaded lazily on first inference and automatically unloaded
 * after [unloadAfterSeconds] of inactivity to free RAM.
 */
class StandaloneLlmEngine(
    private val modelDir: File,
    private val unloadAfterSeconds: Long = 60,
    private val logger: (String) -> Unit
) : LlmEngine {

    private val modelName = "qwen2.5-coder-1.5b-instruct-q4_k_m.gguf"
    private val modelUrl = "https://huggingface.co/Qwen/Qwen2.5-Coder-1.5B-Instruct-GGUF/resolve/main/qwen2.5-coder-1.5b-instruct-q4_k_m.gguf"
    private val minModelSizeBytes = 800_000_000L

    private val lazyManager: LazyModelManager by lazy {
        LazyModelManager(
            modelFile = getModelFile(),
            unloadAfterSeconds = unloadAfterSeconds,
            logger = logger
        )
    }

    fun getModelFile(): File = File(modelDir, modelName)

    override fun isModelDownloaded(): Boolean {
        val file = getModelFile()
        return file.exists() && file.length() >= minModelSizeBytes
    }

    override fun downloadModel(): CompletableFuture<Boolean> {
        val future = CompletableFuture<Boolean>()
        if (isModelDownloaded()) {
            future.complete(true)
            return future
        }

        val modelFile = getModelFile()
        if (modelFile.exists()) {
            logger("Partial/corrupted model file found (${modelFile.length()} bytes), deleting and re-downloading")
            modelFile.delete()
        }

        Thread {
            try {
                Files.createDirectories(modelDir.toPath())
                logger("Downloading model from $modelUrl...")
                val connection = URI(modelUrl).toURL().openConnection()
                connection.getInputStream().use { inputStream ->
                    Files.newOutputStream(getModelFile().toPath()).use { outputStream ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int = inputStream.read(buffer)
                        while (bytesRead != -1) {
                            outputStream.write(buffer, 0, bytesRead)
                            bytesRead = inputStream.read(buffer)
                        }
                    }
                }
                logger("Model download complete.")
                future.complete(true)
            } catch (e: Exception) {
                logger("Download failed: ${e.message}")
                future.completeExceptionally(e)
            }
        }.start()

        return future
    }

    override fun askQuestion(prompt: String, stopStrings: List<String>): String {
        return try {
            lazyManager.infer(prompt, stopStrings)
        } catch (e: Exception) {
            "Error during inference: ${e.message}"
        }
    }

    /**
     * Returns true if the model is currently loaded in RAM.
     */
    fun isModelLoaded(): Boolean = lazyManager.isLoaded()

    /**
     * Forces immediate unload of the model from RAM.
     */
    fun forceUnload() = lazyManager.forceUnload()

    /**
     * Shuts down the engine: unloads model and stops the unload timer.
     * Call on application exit.
     */
    fun close() = lazyManager.shutdown()
}
