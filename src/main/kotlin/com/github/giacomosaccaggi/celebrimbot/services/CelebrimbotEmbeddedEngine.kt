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
import java.net.URL
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

@Service(Service.Level.PROJECT)
class CelebrimbotEmbeddedEngine(private val project: Project) {
    private val LOG = Logger.getInstance(CelebrimbotEmbeddedEngine::class.java)
    private val modelName = "qwen2.5-coder-1.5b-instruct-q4_k_m.gguf"
    private val modelUrl = "https://huggingface.co/Qwen/Qwen2.5-Coder-1.5B-Instruct-GGUF/resolve/main/qwen2.5-coder-1.5b-instruct-q4_k_m.gguf"
    
    private var model: LlamaModel? = null
    private val modelDir = File(PathManager.getSystemPath(), "celebrimbot/models")
    private val executor = Executors.newSingleThreadExecutor()

    private val minModelSizeBytes = 800_000_000L

    fun getModelFile(): File = File(modelDir, modelName)

    fun isModelDownloaded(): Boolean {
        val file = getModelFile()
        return file.exists() && file.length() >= minModelSizeBytes
    }

    fun downloadModel(): CompletableFuture<Boolean> {
        val future = CompletableFuture<Boolean>()
        if (isModelDownloaded()) {
            LOG.info("Model already downloaded at ${modelDir.absolutePath}")
            future.complete(true)
            return future
        }

        val modelFile = getModelFile()
        if (modelFile.exists()) {
            LOG.warn("Partial/corrupted model file found (${modelFile.length()} bytes), deleting and re-downloading")
            modelFile.delete()
        }

        try {
            Files.createDirectories(modelDir.toPath())
        } catch (e: Exception) {
            LOG.error("Failed to create model directory", e)
            future.completeExceptionally(e)
            return future
        }

        LOG.info("Starting model download: $modelUrl")
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Celebrimbot: Forging Local AI (Downloading Qwen...)") {
            override fun run(indicator: ProgressIndicator) {
                try {
                    downloadFile(modelUrl, getModelFile(), indicator, "GGUF Model")
                    LOG.info("Model download finished successfully")
                    future.complete(true)
                } catch (e: Exception) {
                    LOG.error("Failed to download model file", e)
                    future.completeExceptionally(e)
                }
            }
        })
        return future
    }

    private fun downloadFile(url: String, file: File, indicator: ProgressIndicator, name: String) {
        if (file.exists()) return
        
        LOG.info("Downloading $name from $url")
        val connection = URL(url).openConnection()
        val totalSize = connection.contentLengthLong
        
        connection.getInputStream().use { input ->
            Files.newOutputStream(file.toPath()).use { output ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                var totalBytesRead: Long = 0
                
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    if (indicator.isCanceled) {
                        LOG.info("$name download canceled by user")
                        file.delete()
                        throw InterruptedException("$name download canceled")
                    }
                    output.write(buffer, 0, bytesRead)
                    totalBytesRead += bytesRead
                    if (totalSize > 0) {
                        indicator.fraction = totalBytesRead.toDouble() / totalSize
                        indicator.text = "Downloading $name: ${(totalBytesRead / 1024)}KB / ${(totalSize / 1024)}KB"
                    }
                }
            }
        }
    }

    @Synchronized
    fun loadModel() {
        if (model != null) {
            LOG.info("Model already loaded in memory")
            return
        }
        
        val file = getModelFile()
        if (!file.exists()) {
            LOG.warn("Cannot load model: GGUF file not found at ${file.absolutePath}")
            throw IllegalStateException("Model GGUF file not found at ${file.absolutePath}")
        }
        
        LOG.info("Loading LlamaModel into RAM from ${file.absolutePath}...")
        
        val currentThread = Thread.currentThread()
        val oldLoader = currentThread.contextClassLoader
        try {
            currentThread.contextClassLoader = javaClass.classLoader
            
            val modelParams = ModelParameters()
                .setModelFilePath(file.absolutePath)
                .setNGpuLayers(0)
            
            model = LlamaModel(modelParams)
            LOG.info("LlamaModel successfully loaded into RAM")
        } catch (e: Throwable) {
            val errorMessage = "Failed to load GGUF model from ${file.absolutePath}: ${e.message}"
            LOG.error(errorMessage, e)
            throw RuntimeException(errorMessage, e)
        } finally {
            currentThread.contextClassLoader = oldLoader
        }
    }

    @Synchronized
    fun unloadModel() {
        if (model != null) {
            LOG.info("Unloading LlamaModel from RAM...")
            model?.close()
            model = null
            System.gc()
            LOG.info("Model unloaded")
        }
    }

    fun askQuestion(prompt: String, stopStrings: List<String> = emptyList()): String {
        LOG.info("Inference request received")
        
        val future = CompletableFuture.supplyAsync({
            synchronized(this) {
                if (model == null) loadModel()
            }
            
            val m = model ?: throw IllegalStateException("Model not loaded")
            LOG.info("Inference started...")
            val response = StringBuilder()
            
            val inferenceParams = InferenceParameters(prompt)
                .setTemperature(0.7f)
                .setNPredict(256)
                .apply { if (stopStrings.isNotEmpty()) setStopStrings(*stopStrings.toTypedArray()) }
            
            for (output in m.generate(inferenceParams)) {
                response.append(output.text)
            }
            
            LOG.info("Inference finished")
            response.toString().trim()
        }, executor)

        return try {
            future.get(45, TimeUnit.SECONDS) // Slightly longer for Llama.cpp load+infer
        } catch (e: TimeoutException) {
            LOG.warn("Inference timed out after 45 seconds")
            "System: Local engine is too slow or stuck"
        } catch (e: Throwable) {
            val errorMessage = "Inference failed: ${e.message}"
            LOG.error(errorMessage, e)
            "Error: ${e.message}"
        }
    }

    companion object {
        fun getInstance(project: Project): CelebrimbotEmbeddedEngine = project.service()
    }
}
