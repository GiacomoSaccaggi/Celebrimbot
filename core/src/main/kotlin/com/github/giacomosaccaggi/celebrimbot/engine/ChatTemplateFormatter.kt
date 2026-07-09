package com.github.giacomosaccaggi.celebrimbot.engine

/**
 * Represents a single message in a chat conversation.
 */
data class ChatMessage(
    val role: String,   // "system", "user", "assistant", "tool"
    val content: String
)

/**
 * Chat template types matching different model families.
 */
enum class TemplateType {
    /** ChatML format used by Qwen, Yi, and many others: <|im_start|>role\ncontent<|im_end|> */
    CHATML,
    /** Llama 3 instruct format: <|start_header_id|>role<|end_header_id|>\ncontent<|eot_id|> */
    LLAMA3,
    /** Phi-3 format: <|user|>\ncontent<|end|>\n<|assistant|> */
    PHI3,
    /** Generic fallback: ### Role:\ncontent\n\n */
    GENERIC
}

/**
 * Formats a list of [ChatMessage] into a prompt string using the appropriate
 * chat template for the model family.
 *
 * Each model family uses a different special-token format. This formatter
 * ensures the prompt is correctly structured for the target model.
 */
object ChatTemplateFormatter {

    /**
     * Detects the best template type based on the model file name.
     */
    fun detectTemplate(modelFileName: String): TemplateType {
        val lower = modelFileName.lowercase()
        return when {
            "qwen" in lower || "yi-" in lower -> TemplateType.CHATML
            "llama" in lower || "meta-llama" in lower -> TemplateType.LLAMA3
            "phi" in lower -> TemplateType.PHI3
            "deepseek" in lower -> TemplateType.CHATML  // DeepSeek uses ChatML
            else -> TemplateType.CHATML  // Safe default — most models support ChatML
        }
    }

    /**
     * Formats messages into a prompt string ready for inference.
     * Appends the assistant turn start so the model continues from there.
     */
    fun format(messages: List<ChatMessage>, template: TemplateType): String {
        return when (template) {
            TemplateType.CHATML -> formatChatML(messages)
            TemplateType.LLAMA3 -> formatLlama3(messages)
            TemplateType.PHI3 -> formatPhi3(messages)
            TemplateType.GENERIC -> formatGeneric(messages)
        }
    }

    /**
     * Returns the stop strings for the given template type.
     * Used to stop generation at the end of the assistant's turn.
     */
    fun stopStrings(template: TemplateType): List<String> {
        return when (template) {
            TemplateType.CHATML -> listOf("<|im_end|>", "<|im_start|>")
            TemplateType.LLAMA3 -> listOf("<|eot_id|>", "<|start_header_id|>")
            TemplateType.PHI3 -> listOf("<|end|>", "<|user|>")
            TemplateType.GENERIC -> listOf("\n### ")
        }
    }

    // ── ChatML (Qwen, DeepSeek, Yi) ──────────────────────────────────────────

    private fun formatChatML(messages: List<ChatMessage>): String {
        val sb = StringBuilder()
        for (msg in messages) {
            sb.append("<|im_start|>${msg.role}\n${msg.content}<|im_end|>\n")
        }
        sb.append("<|im_start|>assistant\n")
        return sb.toString()
    }

    // ── Llama 3 Instruct ─────────────────────────────────────────────────────

    private fun formatLlama3(messages: List<ChatMessage>): String {
        val sb = StringBuilder("<|begin_of_text|>")
        for (msg in messages) {
            sb.append("<|start_header_id|>${msg.role}<|end_header_id|>\n\n${msg.content}<|eot_id|>")
        }
        sb.append("<|start_header_id|>assistant<|end_header_id|>\n\n")
        return sb.toString()
    }

    // ── Phi-3 ────────────────────────────────────────────────────────────────

    private fun formatPhi3(messages: List<ChatMessage>): String {
        val sb = StringBuilder()
        for (msg in messages) {
            sb.append("<|${msg.role}|>\n${msg.content}<|end|>\n")
        }
        sb.append("<|assistant|>\n")
        return sb.toString()
    }

    // ── Generic fallback ─────────────────────────────────────────────────────

    private fun formatGeneric(messages: List<ChatMessage>): String {
        val sb = StringBuilder()
        for (msg in messages) {
            val label = msg.role.replaceFirstChar { it.uppercase() }
            sb.append("### $label:\n${msg.content}\n\n")
        }
        sb.append("### Assistant:\n")
        return sb.toString()
    }
}
