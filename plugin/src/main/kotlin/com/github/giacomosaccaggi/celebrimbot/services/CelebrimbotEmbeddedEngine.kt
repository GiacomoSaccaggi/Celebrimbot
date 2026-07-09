package com.github.giacomosaccaggi.celebrimbot.services

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

@Service(Service.Level.PROJECT)
class CelebrimbotEmbeddedEngine(private val project: Project) {
    private val log = Logger.getInstance(CelebrimbotEmbeddedEngine::class.java)
    private val modelDir = File(PathManager.getSystemPath(), "celebrimbot/models")
    private val executor = Executors.newSingleThreadExecutor()

    private val minModelSizeBytes = 800_000_000L

    /** Returns the currently selected model from settings. */
    private fun selectedModel(): com.github.giacomosaccaggi.celebrimbot.settings.LocalAiModel =
        com.github.giacomosaccaggi.celebrimbot.settings.CelebrimbotSettingsState
            .getInstance(project).state.selectedLocalModel

    fun getModelFile(): File = File(modelDir, selectedModel().fileName)

    fun isModelDownloaded(): Boolean {
        val file = getModelFile()
        return file.exists() && file.length() >= minModelSizeBytes
    }

    fun downloadModel(): CompletableFuture<Boolean> {
        val future = CompletableFuture<Boolean>()
        if (isModelDownloaded()) {
            log.info("Model already downloaded at ${modelDir.absolutePath}")
            future.complete(true)
            return future
        }

        val modelFile = getModelFile()
        if (modelFile.exists()) {
            log.warn("Partial/corrupted model file found (${modelFile.length()} bytes), deleting and re-downloading")
            modelFile.delete()
        }

        try {
            Files.createDirectories(modelDir.toPath())
        } catch (e: Exception) {
            log.error("Failed to create model directory", e)
            future.completeExceptionally(e)
            return future
        }

        log.info("Starting model download: ${selectedModel().downloadUrl}")
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Celebrimbot: forging local AI (downloading Qwen...)") {
            override fun run(indicator: ProgressIndicator) {
                try {
                    downloadFile(indicator)
                    log.info("Model download finished successfully")
                    future.complete(true)
                } catch (e: Exception) {
                    log.error("Failed to download model file", e)
                    future.completeExceptionally(e)
                }
            }
        })
        return future
    }

    private fun downloadFile(indicator: ProgressIndicator) {
        val file = getModelFile()
        if (file.exists()) return

        val model = selectedModel()
        log.info("Downloading GGUF model from ${model.downloadUrl}")
        val connection = URI(model.downloadUrl).toURL().openConnection()
        val totalSize = connection.contentLengthLong
        
        connection.getInputStream().use { input ->
            Files.newOutputStream(file.toPath()).use { output ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                var totalBytesRead: Long = 0
                
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    if (indicator.isCanceled) {
                        log.info("GGUF model download canceled by user")
                        file.delete()
                        throw InterruptedException("GGUF model download canceled")
                    }
                    output.write(buffer, 0, bytesRead)
                    totalBytesRead += bytesRead
                    if (totalSize > 0) {
                        indicator.fraction = totalBytesRead.toDouble() / totalSize
                        indicator.text = "Downloading GGUF model: ${totalBytesRead / 1024}KB / ${totalSize / 1024}KB"
                    }
                }
            }
        }
    }

    @Synchronized
    fun loadModel() {
        // No-op: model loading is now lazy via LazyModelManager.
        // The model will be loaded automatically on first inference request.
        log.info("loadModel() called — model will load lazily on first use")
    }

    @Synchronized
    fun unloadModel() {
        LocalModelManager.getInstance(project).forceUnload(selectedModel())
        System.gc()
        log.info("Model unloaded via LazyModelManager")
    }

    fun askQuestion(prompt: String, stopStrings: List<String> = emptyList()): String {
        log.info("Inference request received")

        val future = CompletableFuture.supplyAsync({
            val modelEnum = selectedModel()
            val manager = LocalModelManager.getInstance(project)
            val lazyMgr = manager.getManagerFor(modelEnum)

            log.info("Inference started (via LazyModelManager)...")
            val result = lazyMgr.infer(prompt, stopStrings)
            log.info("Inference finished")
            result
        }, executor)

        return try {
            future.get(45, TimeUnit.SECONDS)
        } catch (_: TimeoutException) {
            log.warn("Inference timed out after 45 seconds")
            "System: Local engine is too slow or stuck"
        } catch (e: Throwable) {
            val errorMessage = "Inference failed: ${e.message}"
            log.error(errorMessage, e)
            "Error: ${e.message}"
        }
    }

    companion object {
        fun getInstance(project: Project): CelebrimbotEmbeddedEngine = project.service()
    }
}
