package com.github.giacomosaccaggi.celebrimbot.settings

import com.intellij.openapi.components.*
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
@State(
    name = "CelebrimbotSettingsState",
    storages = [Storage("CelebrimbotSettings.xml")]
)
class CelebrimbotSettingsState : PersistentStateComponent<CelebrimbotSettingsState.State> {

    class State {
        var validationCommand: String = ""
        var selectedLocalModel: LocalAiModel = LocalAiModel.QWEN_7B
        var terminalTimeoutSeconds: Long = 60L

        // ── Cloud model names ─────────────────────────────────────────────────
        var geminiModelName: String = "gemini-1.5-flash"
        var alibabaModelName: String = "qwen-plus"

        // ── Task mode toggles ─────────────────────────────────────────────────
        // When both are disabled Gandalf is bypassed and queries go directly to Galadriel.
        var easyTaskEnabled: Boolean = true
        var complexTaskEnabled: Boolean = true

        // ── External CLI delegation ───────────────────────────────────────────
        // When set, Celebrimbot opens the IDE terminal and delegates the task
        // to an external agent CLI (e.g. "kiro-cli chat", "junie").
        var cliCommand: String = ""

        // ── Custom system prompts ─────────────────────────────────────────────
        // Overridden prompts keyed by character name. Empty = use default from resources.
        var customPrompts: LinkedHashMap<String, String> = LinkedHashMap()

        // ── Per-character agent configuration ─────────────────────────────────
        // Stored as Map<characterKey, AgentConfigDto> so IntelliJ's XML
        // serialiser can round-trip it without custom converters.
        // Access the rich AgentConfig via CelebrimbotSettingsState.getAgentConfig().
        var agentConfigs: LinkedHashMap<String, AgentConfigDto> = buildDefaultAgentConfigs()
    }

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
        // Back-fill any characters that were added after the user's last save
        AgentConfig.ALL_CHARACTERS.forEach { key ->
            myState.agentConfigs.putIfAbsent(key, AgentConfig.defaultFor(key).toDto())
        }
    }

    // ── Convenience accessors ─────────────────────────────────────────────────

    /** Returns the [AgentConfig] for [character], falling back to the default. */
    fun getAgentConfig(character: String): AgentConfig =
        myState.agentConfigs[character]?.toConfig() ?: AgentConfig.defaultFor(character)

    /** Persists an [AgentConfig] for [character]. */
    fun setAgentConfig(character: String, config: AgentConfig) {
        myState.agentConfigs[character] = config.toDto()
    }

    companion object {
        fun getInstance(project: Project): CelebrimbotSettingsState = project.service()
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

private fun buildDefaultAgentConfigs(): LinkedHashMap<String, AgentConfigDto> =
    LinkedHashMap<String, AgentConfigDto>().also { map ->
        AgentConfig.ALL_CHARACTERS.forEach { key ->
            map[key] = AgentConfig.defaultFor(key).toDto()
        }
    }
