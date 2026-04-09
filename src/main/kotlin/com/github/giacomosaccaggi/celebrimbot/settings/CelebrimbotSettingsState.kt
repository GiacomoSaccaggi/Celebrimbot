package com.github.giacomosaccaggi.celebrimbot.settings

import com.intellij.openapi.components.*
import com.intellij.openapi.project.Project

enum class AiProvider {
    GOOGLE_GEMINI,
    LOCAL_API,
    ALIBABA_QWEN
}

@Service(Service.Level.PROJECT)
@State(
    name = "CelebrimbotSettingsState",
    storages = [Storage("CelebrimbotSettings.xml")]
)
class CelebrimbotSettingsState : PersistentStateComponent<CelebrimbotSettingsState.State> {

    class State {
        var provider: AiProvider = AiProvider.LOCAL_API
        var baseUrl: String = "http://localhost:11434/v1/chat/completions"
        var modelName: String = "qwen2.5-coder:1.5b"
    }

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    companion object {
        fun getInstance(project: Project): CelebrimbotSettingsState = project.service()
    }
}
