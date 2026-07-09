package com.github.giacomosaccaggi.celebrimbot.settings

import com.github.giacomosaccaggi.celebrimbot.services.AmazonQCliProvider
import com.github.giacomosaccaggi.celebrimbot.services.CelebrimbotLlmService
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.dsl.builder.*
import java.awt.Component
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
        val promptEditor = JBTextArea(6, 60).apply {
            lineWrap = true
            wrapStyleWord = true
        }
        val promptFilename = PROMPT_FILES[key] ?: ""

        fun load() {
            val cfg = settings.getAgentConfig(key)
            providerBox.selectedItem = cfg.provider
            val custom = settings.state.customPrompts[key]
            promptEditor.text = custom
                ?: if (promptFilename.isNotEmpty()) CelebrimbotLlmService.loadPrompt(promptFilename) else ""
        }

        fun save() {
            val provider = providerBox.selectedItem as? CharacterProvider ?: CharacterProvider.LOCAL
            settings.setAgentConfig(key, AgentConfig(provider = provider, specificModelName = null))
            if (promptFilename.isNotEmpty()) {
                val defaultPrompt = CelebrimbotLlmService.loadPrompt(promptFilename)
                val text = promptEditor.text.trim()
                if (text.isNotEmpty() && text != defaultPrompt.trim()) {
                    settings.state.customPrompts[key] = text
                } else {
                    settings.state.customPrompts.remove(key)
                }
            }
        }

        fun isModified(): Boolean {
            val current = settings.getAgentConfig(key)
            val provider = providerBox.selectedItem as? CharacterProvider ?: CharacterProvider.LOCAL
            if (provider != current.provider) return true
            if (promptFilename.isNotEmpty()) {
                val currentPrompt = settings.state.customPrompts[key]
                    ?: CelebrimbotLlmService.loadPrompt(promptFilename)
                if (promptEditor.text.trim() != currentPrompt.trim()) return true
            }
            return false
        }
    }

    private val characterRows = AgentConfig.ALL_CHARACTERS.map { CharacterRow(it) }

    private val localModelComboBox = ComboBox(LocalAiModel.entries.toTypedArray()).apply {
        renderer = enumRenderer<LocalAiModel> { it.displayName }
    }

    override fun createPanel(): DialogPanel {
        return panel {

            // ═══════════════════════════════════════════════════════════════════
            // 1. TASK MODES
            // ═══════════════════════════════════════════════════════════════════
            group("Task Modes") {
                row {
                    checkBox("Enable EASY_TASK (Aragorn quick path)")
                        .bindSelected(
                            { settings.state.easyTaskEnabled },
                            { settings.state.easyTaskEnabled = it }
                        )
                }
                row {
                    checkBox("Enable COMPLEX_TASK (Elrond + Celebrimbor planning)")
                        .bindSelected(
                            { settings.state.complexTaskEnabled },
                            { settings.state.complexTaskEnabled = it }
                        )
                }
                row {
                    comment(
                        "When both are disabled, Gandalf is bypassed and all messages go directly to Galadriel (chat only).<br/><br/>" +
                        "<b>Architecture — Six-Layer Fellowship Pipeline:</b><br/>" +
                        "<pre>" +
                        "User Message\n" +
                        "     │\n" +
                        "  ┌──▼──────────────────────────────────────────────────────┐\n" +
                        "  │  Gandalf (Router)                                       │\n" +
                        "  │  Classifies the request using conversation history      │\n" +
                        "  └──┬────────────────┬────────────────────┬────────────────┘\n" +
                        "     │                │                    │\n" +
                        "   CHAT          EASY_TASK            COMPLEX_TASK\n" +
                        "     │                │                    │\n" +
                        "     ▼                ▼                    ▼\n" +
                        "  Galadriel       Aragorn              Elrond\n" +
                        "  (direct         (single-pass         (enriches context,\n" +
                        "   chat reply)     multi-task plan)     semantic search)\n" +
                        "                      │                    │\n" +
                        "                      ▼                    ▼\n" +
                        "                 Samwise/Frodo/       Celebrimbor (Cloud)\n" +
                        "                 Legolas &amp; Gimli      (master planner,\n" +
                        "                 (execute tasks,       forges detailed plan)\n" +
                        "                  write code)              │\n" +
                        "                      │                    ▼\n" +
                        "                      ▼               Samwise/Frodo/\n" +
                        "                 Treebeard             Legolas &amp; Gimli\n" +
                        "                 (reviews work,        (execute tasks)\n" +
                        "                  reflection loop)         │\n" +
                        "                      │                    ▼\n" +
                        "                      ▼               Treebeard\n" +
                        "                   Bilbo              (reviews, reflection)\n" +
                        "                 (session summary)         │\n" +
                        "                                           ▼\n" +
                        "                                        Bilbo\n" +
                        "                                      (session summary)\n" +
                        "</pre>"
                    )
                }
            }

            // ═══════════════════════════════════════════════════════════════════
            // 2. COUNCIL'S REVIEW (VALIDATION)
            // ═══════════════════════════════════════════════════════════════════
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

            // ═══════════════════════════════════════════════════════════════════
            // 3. FELLOWSHIP AI CONFIGURATION (per-character: provider + prompt)
            // ═══════════════════════════════════════════════════════════════════
            group("Fellowship AI Configuration") {
                row {
                    comment(
                        "Configure each character's AI provider and system prompt.<br/>" +
                        "Choose <b>External CLI</b> to delegate that character's work to the configured CLI command."
                    )
                }

                for (charRow in characterRows) {
                    val desc = CHARACTER_DESCRIPTIONS[charRow.key] ?: ""
                    collapsibleGroup("${AgentConfig.labelFor(charRow.key)} — $desc") {
                        row("Provider:") {
                            cell(charRow.providerBox).align(AlignX.FILL)
                        }
                        if (charRow.promptFilename.isNotEmpty()) {
                            row("System Prompt:") {
                                cell(JBScrollPane(charRow.promptEditor).apply {
                                    preferredSize = java.awt.Dimension(560, 150)
                                }).align(Align.FILL)
                            }
                            row {
                                button("Reset to Default") {
                                    charRow.promptEditor.text = CelebrimbotLlmService.loadPrompt(charRow.promptFilename)
                                }
                            }
                        }
                    }
                }
            }

            // ═══════════════════════════════════════════════════════════════════
            // 4. LOCAL MODEL
            // ═══════════════════════════════════════════════════════════════════
            group("Local Model") {
                row("GGUF Model:") {
                    cell(localModelComboBox)
                        .align(AlignX.FILL)
                        .comment("Single model loaded in RAM for all LOCAL characters. Downloaded automatically on first use.")
                }
            }

            // ═══════════════════════════════════════════════════════════════════
            // 5. EXTERNAL CLI
            // ═══════════════════════════════════════════════════════════════════
            group("External CLI") {
                row("CLI Command:") {
                    textField()
                        .bindText(
                            { settings.state.cliCommand },
                            { settings.state.cliCommand = it }
                        )
                        .align(AlignX.FILL)
                        .comment(
                            "Command used when a character's provider is set to <b>External CLI</b>.<br/>" +
                            "Use <code>{{MESSAGE}}</code> as placeholder for the user message.<br/>" +
                            "If omitted, the message is appended as an argument.<br/>" +
                            "Examples:<br/>" +
                            "&nbsp;&nbsp;<code>kiro-cli chat --resume --trust-all-tools</code><br/>" +
                            "&nbsp;&nbsp;<code>junie</code><br/>" +
                            "&nbsp;&nbsp;<code>aider --message {{MESSAGE}}</code>"
                        )
                }
                row {
                    comment(
                        "⚠️ The CLI runs as a subprocess. Its stdout is streamed back into the chat panel.<br/>" +
                        "For interactive CLIs that require trust prompts, use the IDE terminal directly."
                    )
                }
            }

            // ═══════════════════════════════════════════════════════════════════
            // 6. CONFIGURE YOUR APIs
            // ═══════════════════════════════════════════════════════════════════
            group("Configure Your APIs") {
                row {
                    comment("API keys, model names, and authentication for cloud providers.")
                }

                // ── Gemini ────────────────────────────────────────────────────
                separator()
                row { label("Google Gemini") }
                row("API Key:") {
                    passwordField()
                        .bindText({ apiKey }, { apiKey = it })
                        .align(AlignX.FILL)
                }
                row("Model Name:") {
                    textField()
                        .bindText(
                            { settings.state.geminiModelName },
                            { settings.state.geminiModelName = it }
                        )
                        .align(AlignX.FILL)
                        .comment("e.g. gemini-1.5-flash, gemini-2.0-flash")
                }

                // ── Alibaba ───────────────────────────────────────────────────
                separator()
                row { label("Alibaba Qwen Cloud") }
                row("API Key:") {
                    passwordField()
                        .bindText({ alibabaApiKey }, { alibabaApiKey = it })
                        .align(AlignX.FILL)
                        .comment("DashScope Responses API")
                }
                row("Model Name:") {
                    textField()
                        .bindText(
                            { settings.state.alibabaModelName },
                            { settings.state.alibabaModelName = it }
                        )
                        .align(AlignX.FILL)
                        .comment("e.g. qwen-plus, qwen-max, qwen-turbo")
                }

                // ── Amazon Q Developer ────────────────────────────────────────
                separator()
                row { label("Amazon Q Developer") }
                row {
                    label("Status:")
                    amazonQStatusLabel = label("Click \"Check status\" to verify").component
                }
                row {
                    button("Check status") {
                        val ok = AmazonQCliProvider.getInstance(project).isAuthenticated()
                        amazonQStatusLabel.text = if (ok) "✅ Authenticated" else "⚠️ Not authenticated"
                    }
                    button("Login with browser") {
                        amazonQStatusLabel.text = "Opening browser..."
                        AmazonQCliProvider.getInstance(project).loginWithBrowser { success ->
                            amazonQStatusLabel.text = if (success) "✅ Authenticated" else "❌ Login failed"
                        }
                    }
                }
                row("SSO Start URL:") {
                    textField()
                        .bindText(
                            { amazonQSettings.state.ssoStartUrl },
                            { amazonQSettings.state.ssoStartUrl = it }
                        )
                        .align(AlignX.FILL)
                        .comment("Leave empty to auto-detect from ~/.aws/sso/cache")
                }
                row("SSO Region:") {
                    textField()
                        .bindText(
                            { amazonQSettings.state.ssoRegion },
                            { amazonQSettings.state.ssoRegion = it }
                        )
                        .align(AlignX.FILL)
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
                    comment("⚠️ Cloud service. Prompts and code context may be sent to AWS.")
                }

                // ── Kiro ──────────────────────────────────────────────────────
                separator()
                row { label("Kiro (AWS)") }
                row {
                    label("Status:")
                    kiroStatusLabel = label("Click \"Check status\" to verify").component
                }
                row {
                    button("Check status") {
                        val ok = AmazonQCliProvider.getInstance(project).isKiroAuthenticated()
                        kiroStatusLabel.text = if (ok) "✅ Authenticated" else "⚠️ Not authenticated"
                    }
                    button("Login with Kiro") {
                        kiroStatusLabel.text = "Opening Kiro login..."
                        AmazonQCliProvider.getInstance(project).loginWithKiro { success ->
                            kiroStatusLabel.text = if (success) "✅ Authenticated" else "❌ Login failed"
                        }
                    }
                }
                row {
                    comment(
                        "Token at <code>~/.aws/sso/cache/kiro-auth-token.json</code>.<br/>" +
                        "⚠️ Cloud service. Prompts and code context may be sent to AWS."
                    )
                }
            }
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

    companion object {
        val PROMPT_FILES = mapOf(
            "Gandalf" to "gandalf_system_prompt.txt",
            "Galadriel" to "galadriel_system_prompt.txt",
            "Aragorn" to "aragorn_system_prompt.txt",
            "Elrond" to "elrond_system_prompt.txt",
            "Celebrimbor" to "celebrimbor_system_prompt.txt",
            "Samwise" to "samwise_system_prompt.txt",
            "Frodo" to "frodo_system_prompt.txt",
            "LegolasGimli" to "legolas_gimli_system_prompt.txt",
            "Treebeard" to "treebeard_system_prompt.txt",
            "Bilbo" to "bilbo_system_prompt.txt"
        )

        val CHARACTER_DESCRIPTIONS = mapOf(
            "Gandalf" to "Router — classifies requests as CHAT / EASY_TASK / COMPLEX_TASK",
            "Galadriel" to "Chat — answers questions and conversations directly",
            "Aragorn" to "Quick Planner — single-pass multi-task plan for EASY_TASK",
            "Elrond" to "Context Enricher — gathers relevant files and context for complex tasks",
            "Celebrimbor" to "Master Planner — forges detailed execution plans (cloud)",
            "Samwise" to "Worker — executes tool calls (read, terminal, search, git)",
            "Frodo" to "Code Writer — generates and writes code to files",
            "LegolasGimli" to "Code Writer (pair) — alternative code writer for complex edits",
            "Treebeard" to "Reviewer — validates completed work, triggers reflection loop",
            "Bilbo" to "Chronicler — writes session summaries after task completion"
        )
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
