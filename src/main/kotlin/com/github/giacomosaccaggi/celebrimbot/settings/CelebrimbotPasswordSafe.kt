package com.github.giacomosaccaggi.celebrimbot.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.project.Project

object CelebrimbotPasswordSafe {

    private fun createCredentialAttributes(project: Project): CredentialAttributes {
        return CredentialAttributes(
            generateServiceName("Celebrimbot", project.locationHash)
        )
    }

    fun getApiKey(project: Project): String? {
        val attributes = createCredentialAttributes(project)
        return PasswordSafe.instance.getPassword(attributes)
    }

    fun setApiKey(project: Project, apiKey: String?) {
        val attributes = createCredentialAttributes(project)
        PasswordSafe.instance.setPassword(attributes, apiKey)
    }

    fun getAlibabaApiKey(project: Project): String? {
        val attributes = CredentialAttributes(
            generateServiceName("Celebrimbot-Alibaba", project.locationHash)
        )
        return PasswordSafe.instance.getPassword(attributes)
    }

    fun setAlibabaApiKey(project: Project, apiKey: String?) {
        val attributes = CredentialAttributes(
            generateServiceName("Celebrimbot-Alibaba", project.locationHash)
        )
        PasswordSafe.instance.setPassword(attributes, apiKey)
    }
}
