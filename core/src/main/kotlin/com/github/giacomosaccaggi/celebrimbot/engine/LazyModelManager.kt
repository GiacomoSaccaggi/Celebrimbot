package com.github.giacomosaccaggi.celebrimbot.engine

import de.kherud.llama.InferenceParameters
import de.kherud.llama.LlamaModel
import de.kherud.llama.ModelParameters
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Manages a single GGUF model with lazy loading and automatic unloading.
 *
 * The model is loaded into RAM only on first inference request.
 * After [unloadAfterSeconds] of inactivity (no infer() calls),
 * the model is automatically closed and RAM is freed.
 *
 * Thread-safe: all state mutations are protected by a ReentrantLock.
 *
 * Lifecycle:
 * ```
 *   UNLOADED ──infer()──► LOADED ──idle timeout──► UNLOADED
 *       ▲                    │
 *       └── timer fires ─────┘
 * ```
 */
class LazyModelManager(
    private val modelFile: File,
    private val unloadAfterSeconds: Long = 60,
    private val nGpuLayers: Int = if (System.getProperty("os.arch") == "aarch64") 99 else 0,
    private val logger: (String) -> Unit = {}
) {
    private val lock = ReentrantLock()
    private var model: LlamaModel? = null
    private var unloadFuture: ScheduledFuture<*>? = null

    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "LazyModelManager-unload-timer").apply { isDaemon = true }
    }

    /**
     * Returns true if the model is currently loaded in RAM.
     */
    fun isLoaded(): Boolean = lock.withLock { model != null }

    /**
     * Performs inference: loads the model if not already in RAM,
     * runs generation, resets the unload timer, and returns the result.
     */
    fun infer(prompt: String, stopStrings: List<String> = emptyList(), temperature: Float = 0.7f, maxTokens: Int = 2048): String {
        val m = ensureLoaded()
        val response = StringBuilder()

        val params = InferenceParameters(prompt)
            .setTemperature(temperature)
            .setNPredict(maxTokens)
            .apply { if (stopStrings.isNotEmpty()) setStopStrings(*stopStrings.toTypedArray()) }

        for (output in m.generate(params)) {
            response.append(output.text)
        }

        // Reset unload timer after successful inference
        rescheduleUnload()

        return response.toString().trim()
    }

    /**
     * Performs streaming inference: loads the model if not already in RAM,
     * generates tokens one by one, calls [onToken] for each, then resets the unload timer.
     * Returns the total number of tokens generated.
     */
    fun inferStreaming(
        prompt: String,
        stopStrings: List<String> = emptyList(),
        temperature: Float = 0.7f,
        maxTokens: Int = 2048,
        onToken: (String) -> Unit
    ): Int {
        val m = ensureLoaded()
        var tokenCount = 0

        val params = InferenceParameters(prompt)
            .setTemperature(temperature)
            .setNPredict(maxTokens)
            .apply { if (stopStrings.isNotEmpty()) setStopStrings(*stopStrings.toTypedArray()) }

        for (output in m.generate(params)) {
            onToken(output.text)
            tokenCount++
        }

        rescheduleUnload()
        return tokenCount
    }

    /**
     * Ensures the model is loaded and returns it.
     * If already loaded, just returns the existing instance (no reload).
     */
    private fun ensureLoaded(): LlamaModel = lock.withLock {
        model?.let { return it }

        logger("[LazyModelManager] Loading model: ${modelFile.name}...")
        check(modelFile.exists()) { "GGUF file not found at ${modelFile.absolutePath}" }

        val thread = Thread.currentThread()
        val originalLoader = thread.contextClassLoader
        val instance = try {
            thread.contextClassLoader = LazyModelManager::class.java.classLoader
            LlamaModel(
                ModelParameters()
                    .setModelFilePath(modelFile.absolutePath)
                    .setNGpuLayers(nGpuLayers)
            )
        } finally {
            thread.contextClassLoader = originalLoader
        }

        model = instance
        logger("[LazyModelManager] Model loaded: ${modelFile.name}")
        rescheduleUnload()
        instance
    }

    /**
     * Cancels any pending unload and schedules a new one.
     */
    private fun rescheduleUnload() {
        unloadFuture?.cancel(false)
        unloadFuture = scheduler.schedule({
            doUnload()
        }, unloadAfterSeconds, TimeUnit.SECONDS)
    }

    /**
     * Unloads the model from RAM, freeing memory.
     */
    private fun doUnload() {
        lock.withLock {
            val m = model ?: return
            logger("[LazyModelManager] Unloading model: ${modelFile.name} (idle timeout ${unloadAfterSeconds}s)")
            try {
                m.close()
            } catch (e: Exception) {
                logger("[LazyModelManager] Error closing model: ${e.message}")
            }
            model = null
        }
        System.gc()
        logger("[LazyModelManager] Model unloaded, RAM freed.")
    }

    /**
     * Forces immediate unload of the model regardless of timer state.
     */
    fun forceUnload() {
        unloadFuture?.cancel(false)
        doUnload()
    }

    /**
     * Shuts down the manager: unloads the model and stops the timer thread.
     * Call this on application shutdown / dispose.
     */
    fun shutdown() {
        unloadFuture?.cancel(false)
        lock.withLock {
            try { model?.close() } catch (_: Exception) {}
            model = null
        }
        scheduler.shutdownNow()
        logger("[LazyModelManager] Shutdown complete.")
    }
}
