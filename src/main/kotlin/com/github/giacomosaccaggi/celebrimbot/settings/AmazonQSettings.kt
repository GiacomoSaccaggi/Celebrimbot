package com.github.giacomosaccaggi.celebrimbot.settings

import com.intellij.openapi.components.*
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
@State(
    name = "AmazonQSettings",
    storages = [Storage("CelebrimbotAmazonQ.xml")]
)
class AmazonQSettings : PersistentStateComponent<AmazonQSettings.State> {

    class State {
        // SSO / IAM Identity Center — left blank, auto-detected from ~/.aws/config at runtime
        var ssoStartUrl: String = ""
        var ssoRegion: String = ""
        var ssoAccountId: String = ""
        var ssoRoleName: String = ""
        // Bedrock model (used only if CodeWhisperer API is unavailable)
        var bedrockRegion: String = ""
        var bedrockModelId: String = ""
        var timeoutMillis: Long = 120_000
        var redactSecrets: Boolean = true
        // Legacy CLI path (kept for users who have the standalone q CLI)
        var cliPath: String = ""
    }

    private var myState = State()
    override fun getState(): State = myState
    override fun loadState(state: State) { myState = state }

    companion object {
        fun getInstance(project: Project): AmazonQSettings = project.service()
    }
}
