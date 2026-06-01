package com.github.giacomosaccaggi.celebrimbot.settings

import com.github.giacomosaccaggi.celebrimbot.services.AmazonQCliProvider
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.dsl.builder.*
import java.awt.Component
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.*

@Suppress("DialogTitleCapitalization")
class CelebrimbotSettingsConfigurable(private val project: Project) : BoundConfigurable("Celebrimbot") {

    private val settings = CelebrimbotSettingsState.getInstance(project)
    private val amazonQSettings = AmazonQSettings.getInstance(project)
    private var apiKey: String = ""
    private var alibabaApiKey: String = ""
    private lateinit var amazonQStatusLabel: JLabel
    private lateinit var kiroStatusLabel: JLabel

    private inner class CharacterRow(val key: String) {
        val providerBox = ComboBox(CharacterProvider.entries.toTypedArray()).apply {
            renderer = enumRenderer<CharacterProvider> { it.displayName }
        }
        val modelNameField = JTextField(16)

        init {
            providerBox.addActionListener {
                val isLocal = providerBox.selectedItem == CharacterProvider.LOCAL
                val isNoModel = providerBox.selectedItem == CharacterProvider.AMAZON_Q
                    || providerBox.selectedItem == CharacterProvider.KIRO
                modelNameField.isVisible = !isLocal && !isNoModel
                modelNameField.isEnabled = !isLocal && !isNoModel
            }
        }

        fun load() {
            val cfg = settings.getAgentConfig(key)
            providerBox.selectedItem  = cfg.provider
            modelNameField.text       = cfg.specificModelName ?: ""
            val isLocal = cfg.provider == CharacterProvider.LOCAL
            val isNoModel = cfg.provider == CharacterProvider.AMAZON_Q
                || cfg.provider == CharacterProvider.KIRO
            modelNameField.isVisible = !isLocal && !isNoModel
            modelNameField.isEnabled = !isLocal && !isNoModel
        }

        fun save() {
            val provider = providerBox.selectedItem as? CharacterProvider ?: CharacterProvider.LOCAL
            settings.setAgentConfig(key, AgentConfig(
                provider          = provider,
                specificModelName = if (provider == CharacterProvider.LOCAL
                    || provider == CharacterProvider.AMAZON_Q
                    || provider == CharacterProvider.KIRO) null
                                    else modelNameField.text.trim().ifBlank { null }
            ))
        }

        fun isModified(): Boolean {
            val current = settings.getAgentConfig(key)
            val provider = providerBox.selectedItem as? CharacterProvider ?: CharacterProvider.LOCAL
            if (provider != current.provider) return true
            val noModel = provider == CharacterProvider.LOCAL
                || provider == CharacterProvider.AMAZON_Q
                || provider == CharacterProvider.KIRO
            return if (!noModel)
                modelNameField.text.trim().ifBlank { null } != current.specificModelName
            else false
        }
    }

    private val characterRows = AgentConfig.ALL_CHARACTERS.map { CharacterRow(it) }

    private val localModelComboBox = ComboBox(LocalAiModel.entries.toTypedArray()).apply {
        renderer = enumRenderer<LocalAiModel> { it.displayName }
    }

    override fun createPanel(): DialogPanel {
        return panel {

            group("API Keys") {
                row("Gemini API Key:") {
                    passwordField()
                        .bindText({ apiKey }, { apiKey = it })
                        .align(AlignX.FILL)
                }
                row("Alibaba Cloud API Key:") {
                    passwordField()
                        .bindText({ alibabaApiKey }, { alibabaApiKey = it })
                        .align(AlignX.FILL)
                        .comment("Used for Qwen Cloud (Responses API)")
                }
            }

            group("Council's Review (Validation)") {
                row("Validation Command:") {
                    textField()
                        .bindText(
                            { settings.state.validationCommand },
                            { settings.state.validationCommand = it }
                        )
                        .align(AlignX.FILL)
                        .comment(
                            "Command to run after every write_code deed. Leave empty to auto-detect.<br/>" +
                            "Examples: <code>./gradlew classes</code>, <code>mvn compile -q</code>"
                        )
                }
            }

            group("Local Model") {
                row("GGUF Model:") {
                    cell(localModelComboBox)
                        .align(AlignX.FILL)
                        .comment("Single model loaded in RAM for all LOCAL characters. Downloaded automatically on first use.")
                }
            }

            group("Fellowship AI Configuration") {
                row {
                    cell(buildCharacterTable())
                        .align(Align.FILL)
                }
            }

            group("Amazon Q Developer") {
                row {
                    label("Status:")
                    amazonQStatusLabel = label("Click \"Check status\" to verify").component
                }
                row {
                    button("Check status") {
                        val ok = AmazonQCliProvider.getInstance(project).isAuthenticated()
                        amazonQStatusLabel.text = if (ok) "✅ Authenticated (token found in ~/.aws/sso/cache)" else "⚠️ Not authenticated — login via Amazon Q plugin first"
                    }
                    button("Login with browser") {
                        amazonQStatusLabel.text = "Opening browser..."
                        AmazonQCliProvider.getInstance(project).loginWithBrowser { success ->
                            amazonQStatusLabel.text = if (success) "✅ Authenticated" else "❌ Login failed — try logging in via the Amazon Q plugin directly"
                        }
                    }
                }
                row {
                    comment(
                        "If you are already logged in via the Amazon Q JetBrains plugin or VSCode extension,<br/>" +
                        "click <b>Check status</b> — Celebrimbot will reuse the existing token automatically."
                    )
                }
                row("SSO Start URL:") {
                    textField()
                        .bindText(
                            { amazonQSettings.state.ssoStartUrl },
                            { amazonQSettings.state.ssoStartUrl = it }
                        )
                        .align(AlignX.FILL)
                        .comment("Leave empty to auto-detect from ~/.aws/sso/cache (any matching token will be used)")
                }
                row("SSO Region:") {
                    textField()
                        .bindText(
                            { amazonQSettings.state.ssoRegion },
                            { amazonQSettings.state.ssoRegion = it }
                        )
                        .align(AlignX.FILL)
                        .comment("e.g. us-east-1 — leave empty to use us-east-1 as default")
                }
                row {
                    button("Auto-detect from ~/.aws/config") {
                        val detected = AmazonQCliProvider.getInstance(project).detectSsoConfigFromAwsConfig()
                        if (detected != null) {
                            amazonQSettings.state.ssoStartUrl = detected.first
                            amazonQSettings.state.ssoRegion = detected.second
                            amazonQStatusLabel.text = "✅ Detected: ${detected.first} (${detected.second})"
                        } else {
                            amazonQStatusLabel.text = "❌ No sso-session found in ~/.aws/config"
                        }
                    }
                }
                row("Timeout (seconds):") {
                    val timeoutField = JTextField(4).apply {
                        text = (amazonQSettings.state.timeoutMillis / 1000L).toString()
                    }
                    cell(timeoutField)
                    comment("Seconds to wait for Amazon Q response")
                    @Suppress("ObjectLiteralToLambda")
                    timeoutField.document.addDocumentListener(object : javax.swing.event.DocumentListener {
                        override fun insertUpdate(e: javax.swing.event.DocumentEvent) = sync()
                        override fun removeUpdate(e: javax.swing.event.DocumentEvent) = sync()
                        override fun changedUpdate(e: javax.swing.event.DocumentEvent) = sync()
                        fun sync() {
                            timeoutField.text.toLongOrNull()?.let {
                                amazonQSettings.state.timeoutMillis = it * 1000L
                            }
                        }
                    })
                }
                row {
                    checkBox("Redact secrets before sending to Amazon Q")
                        .bindSelected(
                            { amazonQSettings.state.redactSecrets },
                            { amazonQSettings.state.redactSecrets = it }
                        )
                }
                row {
                    comment(
                        "⚠️ Amazon Q Developer is a cloud service. Prompts and code context may be sent to AWS.<br/>" +
                        "Only use this provider for code you are allowed to share with Amazon Q Developer."
                    )
                }
            }

            group("Kiro (AWS)") {
                row {
                    label("Status:")
                    kiroStatusLabel = label("Click \"Check status\" to verify").component
                }
                row {
                    button("Check status") {
                        val ok = AmazonQCliProvider.getInstance(project).isKiroAuthenticated()
                        kiroStatusLabel.text = if (ok) "✅ Authenticated (token found in ~/.aws/sso/cache/kiro-auth-token*.json)" else "⚠️ Not authenticated — open Kiro IDE and log in first"
                    }
                    button("Login with Kiro") {
                        kiroStatusLabel.text = "Opening Kiro login..."
                        AmazonQCliProvider.getInstance(project).loginWithKiro { success ->
                            kiroStatusLabel.text = if (success) "✅ Authenticated" else "❌ Login failed — open Kiro IDE and log in manually"
                        }
                    }
                }
                row {
                    comment(
                        "Kiro writes its auth token to <code>~/.aws/sso/cache/kiro-auth-token.json</code>.<br/>" +
                        "If you are already logged in to Kiro IDE, click <b>Check status</b> — no extra login needed.<br/>" +
                        "Kiro uses the same CodeWhisperer API as Amazon Q Developer."
                    )
                }
                row {
                    comment(
                        "⚠️ Kiro is a cloud service. Prompts and code context may be sent to AWS.<br/>" +
                        "Only use this provider for code you are allowed to share with AWS."
                    )
                }
            }
        }
    }

    private fun buildCharacterTable(): JComponent {
        val panel = JPanel(GridBagLayout())
        val gc = GridBagConstraints().apply {
            fill   = GridBagConstraints.HORIZONTAL
            insets = Insets(3, 6, 3, 6)
        }

        fun header(text: String, col: Int, weight: Double) {
            gc.gridx = col; gc.gridy = 0; gc.weightx = weight
            panel.add(JLabel("<html><b>$text</b></html>"), gc)
        }
        header("Character", 0, 0.25)
        header("Provider",  1, 0.45)
        header("Model Name (cloud only)", 2, 0.30)

        characterRows.forEachIndexed { i, row ->
            val y = i + 1
            gc.gridx = 0; gc.gridy = y; gc.weightx = 0.25
            panel.add(JLabel(AgentConfig.labelFor(row.key)), gc)

            gc.gridx = 1; gc.gridy = y; gc.weightx = 0.45
            panel.add(row.providerBox, gc)

            gc.gridx = 2; gc.gridy = y; gc.weightx = 0.30
            panel.add(row.modelNameField, gc)
        }

        gc.gridx = 0; gc.gridy = characterRows.size + 1
        gc.gridwidth = 3; gc.weighty = 1.0; gc.fill = GridBagConstraints.BOTH
        panel.add(JPanel(), gc)

        return JBScrollPane(panel).apply {
            preferredSize = java.awt.Dimension(580, 300)
            border = BorderFactory.createEmptyBorder()
        }
    }

    override fun apply() {
        super.apply()
        CelebrimbotPasswordSafe.setApiKey(project, apiKey)
        CelebrimbotPasswordSafe.setAlibabaApiKey(project, alibabaApiKey)
        (localModelComboBox.selectedItem as? LocalAiModel)?.let {
            settings.state.selectedLocalModel = it
        }
        characterRows.forEach { it.save() }
    }

    override fun reset() {
        super.reset()
        localModelComboBox.selectedItem = settings.state.selectedLocalModel
        characterRows.forEach { it.load() }
        com.intellij.util.concurrency.AppExecutorUtil.getAppExecutorService().submit {
            val k1 = CelebrimbotPasswordSafe.getApiKey(project) ?: ""
            val k2 = CelebrimbotPasswordSafe.getAlibabaApiKey(project) ?: ""
            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                apiKey = k1
                alibabaApiKey = k2
            }
        }
    }

    override fun isModified(): Boolean {
        val modelChanged = localModelComboBox.selectedItem != settings.state.selectedLocalModel
        val agentChanged = characterRows.any { it.isModified() }
        return super.isModified() || modelChanged || agentChanged
    }
}

private inline fun <reified T> enumRenderer(crossinline label: (T) -> String): DefaultListCellRenderer =
    object : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>?, value: Any?, index: Int,
            isSelected: Boolean, cellHasFocus: Boolean
        ): Component {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
            if (value is T) text = label(value)
            return this
        }
    }
