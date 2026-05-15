package com.github.giacomosaccaggi.celebrimbot.settings

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
    private var apiKey: String = ""
    private var alibabaApiKey: String = ""

    private inner class CharacterRow(val key: String) {
        val providerBox = ComboBox(CharacterProvider.entries.toTypedArray()).apply {
            renderer = enumRenderer<CharacterProvider> { it.displayName }
        }
        val modelNameField = JTextField(16)

        init {
            providerBox.addActionListener {
                val isLocal = providerBox.selectedItem == CharacterProvider.LOCAL
                modelNameField.isVisible = !isLocal
                modelNameField.isEnabled = !isLocal
            }
        }

        fun load() {
            val cfg = settings.getAgentConfig(key)
            providerBox.selectedItem  = cfg.provider
            modelNameField.text       = cfg.specificModelName ?: ""
            val isLocal = cfg.provider == CharacterProvider.LOCAL
            modelNameField.isVisible = !isLocal
            modelNameField.isEnabled = !isLocal
        }

        fun save() {
            val provider = providerBox.selectedItem as? CharacterProvider ?: CharacterProvider.LOCAL
            settings.setAgentConfig(key, AgentConfig(
                provider          = provider,
                specificModelName = if (provider == CharacterProvider.LOCAL) null
                                    else modelNameField.text.trim().ifBlank { null }
            ))
        }

        fun isModified(): Boolean {
            val current = settings.getAgentConfig(key)
            val provider = providerBox.selectedItem as? CharacterProvider ?: CharacterProvider.LOCAL
            if (provider != current.provider) return true
            return if (provider != CharacterProvider.LOCAL)
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
