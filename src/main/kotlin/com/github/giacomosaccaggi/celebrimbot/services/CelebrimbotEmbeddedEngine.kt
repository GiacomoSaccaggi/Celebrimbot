package com.github.giacomosaccaggi.celebrimbot.services

import de.kherud.llama.InferenceParameters
import de.kherud.llama.LlamaModel
import de.kherud.llama.ModelParameters
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
    private var model: LlamaModel? = null
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
        // Delegate to LocalModelManager — it handles lazy loading and caching
        try {
            LocalModelManager.getInstance(project).getOrLoadModel(selectedModel())
            log.info("Model available via LocalModelManager")
        } catch (e: Exception) {
            log.error("loadModel() failed", e)
            throw e
        }
    }

    @Synchronized
    fun unloadModel() {
        // Unload only the currently selected model from the cache
        LocalModelManager.getInstance(project).forceUnload(selectedModel())
        // Also clear the legacy single-model field for backward compatibility
        model?.close()
        model = null
        System.gc()
        log.info("Model unloaded via LocalModelManager")
    }

    fun askQuestion(prompt: String, stopStrings: List<String> = emptyList()): String {
        log.info("Inference request received")

        val future = CompletableFuture.supplyAsync({
            // Resolve the model to use for this inference
            val modelEnum = selectedModel()
            val manager = LocalModelManager.getInstance(project)
            val m = try {
                manager.getOrLoadModel(modelEnum)
            } catch (e: Exception) {
                log.error("Failed to load model via LocalModelManager", e)
                throw e
            }

            log.info("Inference started...")
            val response = StringBuilder()

            val inferenceParams = InferenceParameters(prompt)
                .setTemperature(0.7f)
                .setNPredict(2048)
                .apply { if (stopStrings.isNotEmpty()) setStopStrings(*stopStrings.toTypedArray()) }

            for (output in m.generate(inferenceParams)) {
                response.append(output.text)
            }

            // Touch the cache entry so the TTL clock resets after a successful inference
            manager.touch(modelEnum)

            log.info("Inference finished")
            response.toString().trim()
        }, executor)

        return try {
            future.get(45, TimeUnit.SECONDS) // Slightly longer for Llama.cpp load+infer
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
