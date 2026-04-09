package com.github.giacomosaccaggi.celebrimbot.settings

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.*

class CelebrimbotSettingsConfigurable(private val project: Project) : BoundConfigurable("Celebrimbot") {

    private val settings = CelebrimbotSettingsState.getInstance(project)
    private var apiKey: String = ""
    private var alibabaApiKey: String = ""

    override fun createPanel(): DialogPanel {
        return panel {
            group("Provider Settings") {
                buttonsGroup("Provider:") {
                    row {
                        radioButton("Google Gemini", AiProvider.GOOGLE_GEMINI)
                        radioButton("Local API (Ollama)", AiProvider.LOCAL_API)
                        radioButton("Alibaba Qwen Cloud", AiProvider.ALIBABA_QWEN)
                    }
                }.bind(
                    { settings.state.provider },
                    { settings.state.provider = it }
                )

                row("Base URL:") {
                    textField()
                        .bindText(
                            { settings.state.baseUrl },
                            { settings.state.baseUrl = it }
                        )
                        .align(AlignX.FILL)
                        .comment("Ollama: http://localhost:11434/v1/chat/completions<br/>Gemini: https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent<br/>Alibaba: https://dashscope-intl.aliyuncs.com/compatible-mode/v1")
                }

                row("Model Name:") {
                    textField()
                        .bindText(
                            { settings.state.modelName },
                            { settings.state.modelName = it }
                        )
                        .align(AlignX.FILL)
                        .comment("e.g., qwen2.5-coder:1.5b for Ollama, gemini-1.5-flash for Gemini, qwen-plus for Alibaba")
                }

                row("API Key (Gemini / Ollama):") {
                    passwordField()
                        .bindText(
                            { apiKey },
                            { apiKey = it }
                        )
                        .align(AlignX.FILL)
                }

                row("Alibaba Cloud API Key:") {
                    passwordField()
                        .bindText(
                            { alibabaApiKey },
                            { alibabaApiKey = it }
                        )
                        .align(AlignX.FILL)
                        .comment("Used for Qwen Cloud (Responses API) — planner + web_search, code_interpreter")
                }
            }
        }
    }

    override fun apply() {
        super.apply()
        CelebrimbotPasswordSafe.setApiKey(project, apiKey)
        CelebrimbotPasswordSafe.setAlibabaApiKey(project, alibabaApiKey)
    }

    override fun reset() {
        super.reset()
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
        return super.isModified()
    }
}
