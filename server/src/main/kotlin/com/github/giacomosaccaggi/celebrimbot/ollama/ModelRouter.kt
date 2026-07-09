package com.github.giacomosaccaggi.celebrimbot.ollama

import com.github.giacomosaccaggi.celebrimbot.engine.ChatMessage
import com.github.giacomosaccaggi.celebrimbot.engine.ChatTemplateFormatter
import com.github.giacomosaccaggi.celebrimbot.engine.LazyModelManager
import com.github.giacomosaccaggi.celebrimbot.engine.TemplateType
import com.github.giacomosaccaggi.celebrimbot.settings.LocalAiModel
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Routes Ollama-style model names (e.g. "qwen2.5-coder:1.5b") to the appropriate
 * GGUF file and LazyModelManager instance.
 *
 * Only one model is loaded in RAM at a time. Requesting a different model
 * shuts down the current one and loads the new one.
 *
 * Model name resolution:
 * - Exact GGUF filename match (e.g. "qwen2.5-coder-1.5b-instruct-q4_k_m.gguf")
 * - Ollama-style short name (e.g. "qwen2.5-coder:1.5b")
 * - Fallback to default model if name is empty
 */
class ModelRouter(
    private val modelDir: File = File(System.getProperty("user.home"), ".celebrimbot/models"),
    private val unloadAfterSeconds: Long = 300,
    private val logger: (String) -> Unit = { println("[ModelRouter] $it") }
) {
    private val lock = ReentrantLock()
    private var currentModelName: String? = null
    private var currentManager: LazyModelManager? = null
    private var currentTemplate: TemplateType? = null

    /** All known model name aliases mapped to their GGUF file info. */
    private val modelAliases: Map<String, ModelInfo> = buildAliasMap()

    data class ModelInfo(
        val name: String,           // Display name (Ollama-style: "qwen2.5-coder:1.5b")
        val fileName: String,       // GGUF filename on disk
        val family: String,         // Model family (e.g. "qwen2")
        val parameterSize: String,  // e.g. "1.5B"
        val quantization: String,   // e.g. "Q4_K_M"
        val template: TemplateType,
        val localAiModel: LocalAiModel? = null // Reference to enum for download
    )

    /**
     * Returns all models available on disk (downloaded GGUF files).
     */
    fun listAvailableModels(): List<ModelInfo> {
        if (!modelDir.exists()) return emptyList()
        val files = modelDir.listFiles { f -> f.extension == "gguf" && f.length() > 100_000_000 }
            ?: return emptyList()
        return files.mapNotNull { file ->
            modelAliases.values.find { it.fileName == file.name }
        }.distinctBy { it.name }
    }

    /**
     * Returns the currently loaded model, or null if none is loaded.
     */
    fun getRunningModel(): ModelInfo? = lock.withLock {
        if (currentManager?.isLoaded() == true) {
            modelAliases.values.find { it.name == currentModelName }
        } else null
    }

    /**
     * Resolves a model name to its ModelInfo. Returns null if unknown.
     */
    fun resolveModel(name: String): ModelInfo? {
        if (name.isBlank()) return listAvailableModels().firstOrNull()
        return modelAliases[name.lowercase()]
            ?: modelAliases.values.find { it.fileName.lowercase() == name.lowercase() }
            ?: listAvailableModels().firstOrNull()
    }

    /**
     * Performs inference with the specified model. Loads it if needed.
     */
    fun infer(modelName: String, prompt: String, temperature: Float = 0.7f, maxTokens: Int = 2048, stopStrings: List<String> = emptyList()): String {
        val mgr = getOrCreateManager(modelName)
        return mgr.infer(prompt, stopStrings, temperature, maxTokens)
    }

    /**
     * Performs streaming inference. Calls [onToken] for each generated token.
     * Returns total token count.
     */
    fun inferStreaming(modelName: String, prompt: String, temperature: Float = 0.7f, maxTokens: Int = 2048, stopStrings: List<String> = emptyList(), onToken: (String) -> Unit): Int {
        val mgr = getOrCreateManager(modelName)
        return mgr.inferStreaming(prompt, stopStrings, temperature, maxTokens, onToken)
    }

    /**
     * Formats chat messages into a prompt string using the appropriate template for the model.
     */
    fun formatChat(modelName: String, messages: List<ChatMessage>): Pair<String, List<String>> {
        val info = resolveModel(modelName)
        val template = info?.template ?: TemplateType.CHATML
        val prompt = ChatTemplateFormatter.format(messages, template)
        val stops = ChatTemplateFormatter.stopStrings(template)
        return prompt to stops
    }

    /**
     * Returns the template type for a given model name.
     */
    fun getTemplate(modelName: String): TemplateType {
        return resolveModel(modelName)?.template ?: TemplateType.CHATML
    }

    /**
     * Gets or creates a LazyModelManager for the given model.
     * If a different model is currently loaded, shuts it down first.
     */
    private fun getOrCreateManager(modelName: String): LazyModelManager = lock.withLock {
        val info = resolveModel(modelName)
            ?: throw IllegalArgumentException("Unknown model: $modelName")

        val resolvedName = info.name

        // Same model already managed
        if (currentModelName == resolvedName && currentManager != null) {
            return currentManager!!
        }

        // Different model: shut down current
        currentManager?.shutdown()
        logger("Switching to model: $resolvedName (${info.fileName})")

        val file = File(modelDir, info.fileName)
        if (!file.exists()) {
            throw IllegalStateException("Model file not found: ${file.absolutePath}. Run 'celebrimbot download-model -m ${resolvedName}' first.")
        }

        val mgr = LazyModelManager(
            modelFile = file,
            unloadAfterSeconds = unloadAfterSeconds,
            logger = logger
        )
        currentModelName = resolvedName
        currentManager = mgr
        currentTemplate = info.template
        mgr
    }

    /**
     * Builds the alias map: multiple name variants → same ModelInfo.
     */
    private fun buildAliasMap(): Map<String, ModelInfo> {
        val map = mutableMapOf<String, ModelInfo>()

        val qwen15 = ModelInfo("qwen2.5-coder:1.5b", "qwen2.5-coder-1.5b-instruct-q4_k_m.gguf", "qwen2", "1.5B", "Q4_K_M", TemplateType.CHATML, LocalAiModel.QWEN_1_5B)
        listOf("qwen2.5-coder:1.5b", "qwen2.5-coder:1.5b-instruct-q4_k_m", "qwen-1.5b", "qwen:1.5b", "qwen2.5-coder-1.5b-instruct-q4_k_m.gguf").forEach { map[it] = qwen15 }

        val qwen7 = ModelInfo("qwen2.5-coder:7b", "qwen2.5-coder-7b-instruct-q4_k_m.gguf", "qwen2", "7B", "Q4_K_M", TemplateType.CHATML, LocalAiModel.QWEN_7B)
        listOf("qwen2.5-coder:7b", "qwen2.5-coder:7b-instruct-q4_k_m", "qwen-7b", "qwen:7b", "qwen2.5-coder-7b-instruct-q4_k_m.gguf").forEach { map[it] = qwen7 }

        val llama8 = ModelInfo("llama3.1:8b", "Meta-Llama-3.1-8B-Instruct-Q4_K_M.gguf", "llama", "8B", "Q4_K_M", TemplateType.LLAMA3, LocalAiModel.LLAMA_3_1_8B)
        listOf("llama3.1:8b", "llama3:8b", "llama-8b", "llama:8b", "meta-llama-3.1-8b-instruct-q4_k_m.gguf").forEach { map[it] = llama8 }

        val deepseek = ModelInfo("deepseek-coder:6.7b", "deepseek-coder-6.7b-instruct.Q4_K_M.gguf", "deepseek", "6.7B", "Q4_K_M", TemplateType.CHATML, LocalAiModel.DEEPSEEK_CODER_6_7B)
        listOf("deepseek-coder:6.7b", "deepseek:6.7b", "deepseek-6.7b", "deepseek-coder-6.7b-instruct.q4_k_m.gguf").forEach { map[it] = deepseek }

        val phi = ModelInfo("phi3.5:3.8b", "Phi-3.5-mini-instruct-Q4_K_M.gguf", "phi3", "3.8B", "Q4_K_M", TemplateType.PHI3, LocalAiModel.PHI_3_5_MINI)
        listOf("phi3.5:3.8b", "phi3.5:latest", "phi-3.5", "phi:3.5", "phi3.5-mini-instruct-q4_k_m.gguf").forEach { map[it] = phi }

        return map
    }

    fun shutdown() = lock.withLock {
        currentManager?.shutdown()
        currentManager = null
        currentModelName = null
    }
}
