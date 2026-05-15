package com.github.giacomosaccaggi.celebrimbot.settings

/**
 * The catalogue of local GGUF models that Celebrimbot can download and run
 * via java-llama.cpp. Each entry carries everything needed to present the
 * model to the user and to fetch it from HuggingFace if it is missing.
 */
enum class LocalAiModel(
    val displayName: String,
    val fileName: String,
    val downloadUrl: String
) {
    QWEN_1_5B(
        displayName  = "Qwen 2.5 Coder 1.5B (Fast, ~1.2GB)",
        fileName     = "qwen2.5-coder-1.5b-instruct-q4_k_m.gguf",
        downloadUrl  = "https://huggingface.co/Qwen/Qwen2.5-Coder-1.5B-Instruct-GGUF/resolve/main/qwen2.5-coder-1.5b-instruct-q4_k_m.gguf"
    ),
    QWEN_7B(
        displayName  = "Qwen 2.5 Coder 7B (Powerful, ~4.5GB)",
        fileName     = "qwen2.5-coder-7b-instruct-q4_k_m.gguf",
        downloadUrl  = "https://huggingface.co/Qwen/Qwen2.5-Coder-7B-Instruct-GGUF/resolve/main/qwen2.5-coder-7b-instruct-q4_k_m.gguf"
    ),
    LLAMA_3_1_8B(
        displayName  = "Llama 3.1 8B Instruct (All-rounder, ~5GB)",
        fileName     = "Meta-Llama-3.1-8B-Instruct-Q4_K_M.gguf",
        downloadUrl  = "https://huggingface.co/bartowski/Meta-Llama-3.1-8B-Instruct-GGUF/resolve/main/Meta-Llama-3.1-8B-Instruct-Q4_K_M.gguf"
    ),
    DEEPSEEK_CODER_6_7B(
        displayName  = "DeepSeek Coder 6.7B (Expert Coder, ~4.5GB)",
        fileName     = "deepseek-coder-6.7b-instruct.Q4_K_M.gguf",
        downloadUrl  = "https://huggingface.co/TheBloke/deepseek-coder-6.7B-instruct-GGUF/resolve/main/deepseek-coder-6.7b-instruct.Q4_K_M.gguf"
    ),
    PHI_3_5_MINI(
        displayName  = "Phi-3.5 Mini 3.8B (Lightweight, ~2.5GB)",
        fileName     = "Phi-3.5-mini-instruct-Q4_K_M.gguf",
        downloadUrl  = "https://huggingface.co/bartowski/Phi-3.5-mini-instruct-GGUF/resolve/main/Phi-3.5-mini-instruct-Q4_K_M.gguf"
    );

    override fun toString(): String = displayName
}
