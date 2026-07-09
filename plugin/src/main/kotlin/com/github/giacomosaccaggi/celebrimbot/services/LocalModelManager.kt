package com.github.giacomosaccaggi.celebrimbot.services

import com.github.giacomosaccaggi.celebrimbot.engine.LazyModelManager
import com.github.giacomosaccaggi.celebrimbot.settings.LocalAiModel
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import de.kherud.llama.LlamaModel
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * IDE model manager: delegates to [LazyModelManager] from core for automatic
 * lazy-load and auto-unload behavior.
 *
 * The model is loaded only on first inference request and automatically freed
 * from RAM after [unloadTimeoutSeconds] of inactivity (default 60s).
 *
 * Switching to a different model shuts down the current manager and creates a new one.
 */
@Service(Service.Level.PROJECT)
class LocalModelManager(private val project: Project) {

    private val log = Logger.getInstance(LocalModelManager::class.java)
    private val modelDir = File(PathManager.getSystemPath(), "celebrimbot/models")
    private val lock = ReentrantLock()

    private var currentModel: LocalAiModel? = null
    private var lazyManager: LazyModelManager? = null

    /**
     * Timeout in seconds before the model is unloaded from RAM after last use.
     * Configurable via CelebrimbotSettingsState.modelUnloadTimeoutSeconds.
     */
    var unloadTimeoutSeconds: Long = 60

    /**
     * Returns the [LazyModelManager] for the given model, creating one if needed.
     * If a different model was previously loaded, it is shut down first.
     */
    fun getManagerFor(model: LocalAiModel): LazyModelManager = lock.withLock {
        if (currentModel == model && lazyManager != null) {
            return lazyManager!!
        }
        // Switching models: shut down old manager
        if (currentModel != model) {
            shutdownCurrentLocked()
        }
        log.info("[LocalModelManager] Creating LazyModelManager for ${model.fileName} (timeout=${unloadTimeoutSeconds}s)")
        val file = File(modelDir, model.fileName)
        val mgr = LazyModelManager(
            modelFile = file,
            unloadAfterSeconds = unloadTimeoutSeconds,
            logger = { msg -> log.info(msg) }
        )
        currentModel = model
        lazyManager = mgr
        mgr
    }

    /**
     * Legacy compatibility: returns the LlamaModel instance directly.
     * Triggers a load if the model is not in RAM.
     * NOTE: Prefer using getManagerFor() and calling infer() on it directly.
     */
    fun getOrLoadModel(model: LocalAiModel): LlamaModel {
        // For backward compat with CelebrimbotEmbeddedEngine which still expects a LlamaModel.
        // This path loads eagerly but the LazyModelManager's timer will still auto-unload.
        val file = File(modelDir, model.fileName)
        check(file.exists()) { "GGUF file not found at ${file.absolutePath}." }
        val thread = Thread.currentThread()
        val originalLoader = thread.contextClassLoader
        return try {
            thread.contextClassLoader = LocalModelManager::class.java.classLoader
            de.kherud.llama.LlamaModel(
                de.kherud.llama.ModelParameters()
                    .setModelFilePath(file.absolutePath)
                    .setNGpuLayers(if (System.getProperty("os.arch") == "aarch64") 99 else 0)
            )
        } catch (e: Throwable) {
            throw RuntimeException("Failed to load '${model.fileName}': ${e.message}", e)
        } finally {
            thread.contextClassLoader = originalLoader
        }
    }

    fun touch(@Suppress("UNUSED_PARAMETER") model: LocalAiModel) { /* no-op */ }

    fun forceUnload(model: LocalAiModel) = lock.withLock {
        if (currentModel == model) {
            lazyManager?.forceUnload()
        }
    }

    fun forceUnloadAll() = lock.withLock {
        shutdownCurrentLocked()
        System.gc()
    }

    fun isModelLoaded(): Boolean = lazyManager?.isLoaded() ?: false

    fun loadedModels(): Set<LocalAiModel> =
        if (lazyManager?.isLoaded() == true) setOf(currentModel!!) else emptySet()

    private fun shutdownCurrentLocked() {
        try { lazyManager?.shutdown() } catch (e: Exception) {
            log.warn("[LocalModelManager] Error shutting down ${currentModel?.fileName}: ${e.message}")
        }
        lazyManager = null
        currentModel = null
    }

    companion object {
        fun getInstance(project: Project): LocalModelManager = project.service()
    }
}
