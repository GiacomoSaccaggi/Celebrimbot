package com.github.giacomosaccaggi.celebrimbot.io

import de.kherud.llama.InferenceParameters
import de.kherud.llama.LlamaModel
import de.kherud.llama.ModelParameters
import java.io.File
import java.net.URL
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Standalone implementation of LlmEngine using java-llama.cpp.
 * No IntelliJ dependencies.
 */
class StandaloneLlmEngine(private val modelDir: File, private val logger: (String) -> Unit) : LlmEngine {
    
    private val modelName = "qwen2.5-coder-1.5b-instruct-q4_k_m.gguf"
    private val modelUrl = "https://huggingface.co/Qwen/Qwen2.5-Coder-1.5B-Instruct-GGUF/resolve/main/qwen2.5-coder-1.5b-instruct-q4_k_m.gguf"
    
    private var model: LlamaModel? = null
    private val executor = Executors.newSingleThreadExecutor()

    private val minModelSizeBytes = 800_000_000L

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
                val connection = URL(modelUrl).openConnection()
                val inputStream = connection.getInputStream()
                try {
                    val outputStream = Files.newOutputStream(getModelFile().toPath())
                    try {
                        val buffer = ByteArray(8192)
                        var bytesRead: Int = inputStream.read(buffer)
                        while (bytesRead != -1) {
                            outputStream.write(buffer, 0, bytesRead)
                            bytesRead = inputStream.read(buffer)
                        }
                    } finally {
                        outputStream.close()
                    }
                } finally {
                    inputStream.close()
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

    private fun loadModel() {
        if (model != null) return
        
        val file = getModelFile()
        if (!file.exists()) {
            throw IllegalStateException("Model file not found at " + file.absolutePath)
        }
        
        logger("Loading LlamaModel into RAM (Hardware Acceleration: AUTO)...")
        val currentThread = Thread.currentThread()
        val oldLoader = currentThread.contextClassLoader
        try {
            currentThread.setContextClassLoader(StandaloneLlmEngine::class.java.classLoader)
            
            // Optimization: java-llama.cpp handles native loading via ModelParameters.
            // On macOS (Apple Silicon), we maximize GPU layers for Metal acceleration.
            val nGpuLayers = if (System.getProperty("os.arch") == "aarch64") 99 else 0
            
            val modelParams = ModelParameters()
                .setModelFilePath(file.absolutePath)
                .setNGpuLayers(nGpuLayers)
            
            model = LlamaModel(modelParams)
            logger("LlamaModel loaded with " + nGpuLayers + " GPU layers.")
        } finally {
            currentThread.setContextClassLoader(oldLoader)
        }
    }

    override fun askQuestion(prompt: String, stopStrings: List<String>): String {
        val future = CompletableFuture.supplyAsync({
            loadModel()
            
            val m = model ?: throw IllegalStateException("Model not loaded")
            val response = StringBuilder()
            val params = InferenceParameters(prompt)
                .setTemperature(0.7f)
                .setNPredict(512)
            
            val generator = m.generate(params).iterator()
            while (generator.hasNext()) {
                response.append(generator.next().text)
            }
            response.toString()
        }, executor)

        return try {
            future.get(60, TimeUnit.SECONDS)
        } catch (e: Exception) {
            "Error during inference: ${e.message}"
        }
    }
}
