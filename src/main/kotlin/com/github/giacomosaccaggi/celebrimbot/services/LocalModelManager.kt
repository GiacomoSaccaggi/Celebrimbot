package com.github.giacomosaccaggi.celebrimbot.services

import com.github.giacomosaccaggi.celebrimbot.settings.LocalAiModel
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import de.kherud.llama.LlamaModel
import de.kherud.llama.ModelParameters
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Single-slot model manager: only one LlamaModel lives in RAM at a time.
 * Loading a different model automatically unloads the current one first.
 */
@Service(Service.Level.PROJECT)
class LocalModelManager(private val project: Project) {

    private val log = Logger.getInstance(LocalModelManager::class.java)
    private val modelDir = File(PathManager.getSystemPath(), "celebrimbot/models")
    private val lock = ReentrantLock()

    private var loadedModel: LocalAiModel? = null
    private var loadedInstance: LlamaModel? = null

    fun getOrLoadModel(model: LocalAiModel): LlamaModel = lock.withLock {
        if (loadedModel == model && loadedInstance != null) {
            return loadedInstance!!
        }
        // Unload previous model if different
        if (loadedModel != model) {
            closeCurrentLocked()
        }
        log.info("[LocalModelManager] Loading ${model.fileName}...")
        val instance = loadFromDisk(model)
        loadedModel = model
        loadedInstance = instance
        log.info("[LocalModelManager] ${model.fileName} loaded.")
        instance
    }

    fun touch(@Suppress("UNUSED_PARAMETER") model: LocalAiModel) { /* no-op: single slot, no TTL needed */ }

    fun forceUnload(model: LocalAiModel) = lock.withLock {
        if (loadedModel == model) closeCurrentLocked()
    }

    fun forceUnloadAll() = lock.withLock {
        closeCurrentLocked()
        System.gc()
    }

    fun loadedModels(): Set<LocalAiModel> =
        loadedInstance?.let { setOf(loadedModel!!) } ?: emptySet()

    private fun closeCurrentLocked() {
        try { loadedInstance?.close() } catch (e: Exception) {
            log.warn("[LocalModelManager] Error closing ${loadedModel?.fileName}: ${e.message}")
        }
        loadedInstance = null
        loadedModel = null
    }

    private fun loadFromDisk(model: LocalAiModel): LlamaModel {
        val file = File(modelDir, model.fileName)
        check(file.exists()) { "GGUF file not found at ${file.absolutePath}." }
        val thread = Thread.currentThread()
        val originalLoader = thread.contextClassLoader
        return try {
            thread.contextClassLoader = LocalModelManager::class.java.classLoader
            LlamaModel(ModelParameters().setModelFilePath(file.absolutePath).setNGpuLayers(gpuLayers()))
        } catch (e: Throwable) {
            throw RuntimeException("Failed to load '${model.fileName}': ${e.message}", e)
        } finally {
            thread.contextClassLoader = originalLoader
        }
    }

    private fun gpuLayers(): Int = if (System.getProperty("os.arch") == "aarch64") 99 else 0

    companion object {
        fun getInstance(project: Project): LocalModelManager = project.service()
    }
}
