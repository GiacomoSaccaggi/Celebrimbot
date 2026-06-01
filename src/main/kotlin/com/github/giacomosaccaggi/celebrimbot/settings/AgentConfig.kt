package com.github.giacomosaccaggi.celebrimbot.settings

/**
 * The AI provider a single Fellowship character uses for inference.
 */
enum class CharacterProvider(val displayName: String) {
    LOCAL("Local (embedded)"),
    GOOGLE_GEMINI("Google Gemini"),
    ALIBABA_QWEN("Alibaba Qwen Cloud"),
    AMAZON_Q("Amazon Q Developer"),
    KIRO("Kiro (AWS)");

    override fun toString(): String = displayName
}

/**
 * The complete AI configuration for one Fellowship character.
 *
 * @param provider          which inference backend to use.
 * @param specificModelName the API model name (e.g. "qwen-plus") when
 *                          [provider] is cloud-based. Null means "use the default".
 */
data class AgentConfig(
    val provider: CharacterProvider,
    val specificModelName: String? = null
) {
    companion object {
        val DEFAULT_LOCAL   = AgentConfig(CharacterProvider.LOCAL)
        val DEFAULT_ALIBABA = AgentConfig(CharacterProvider.ALIBABA_QWEN, specificModelName = "qwen-plus")
        val DEFAULT_GEMINI  = AgentConfig(CharacterProvider.GOOGLE_GEMINI, specificModelName = "gemini-1.5-flash")

        fun defaultFor(character: String): AgentConfig = when (character) {
            "Celebrimbor" -> DEFAULT_ALIBABA
            else          -> DEFAULT_LOCAL
        }

        val ALL_CHARACTERS = listOf(
            "Gandalf", "Galadriel", "Aragorn", "Elrond", "Celebrimbor",
            "Samwise", "Frodo", "LegolasGimli", "Treebeard", "Bilbo"
        )

        fun labelFor(character: String): String = when (character) {
            "LegolasGimli" -> "Legolas & Gimli"
            else           -> character
        }
    }
}

// ── XML-serialisation DTO ─────────────────────────────────────────────────────

class AgentConfigDto {
    var provider: String = CharacterProvider.LOCAL.name
    var specificModelName: String = ""
}

fun AgentConfig.toDto(): AgentConfigDto = AgentConfigDto().also { dto ->
    dto.provider          = provider.name
    dto.specificModelName = specificModelName ?: ""
}

fun AgentConfigDto.toConfig(): AgentConfig = AgentConfig(
    provider          = runCatching { CharacterProvider.valueOf(provider) }.getOrDefault(CharacterProvider.LOCAL),
    specificModelName = specificModelName.ifBlank { null }
)
