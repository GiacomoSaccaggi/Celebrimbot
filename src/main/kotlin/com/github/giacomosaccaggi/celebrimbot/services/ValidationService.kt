package com.github.giacomosaccaggi.celebrimbot.services

import com.github.giacomosaccaggi.celebrimbot.settings.CelebrimbotSettingsState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import java.io.File

/**
 * The Council's Review — detects the project's build system and resolves
 * the validation command that must pass after every write_code deed.
 *
 * Detection is done by probing for well-known marker files relative to
 * the project base path. The user may override the auto-detected command
 * via Settings → Celebrimbot → Validation Command.
 */
@Service(Service.Level.PROJECT)
class ValidationService(private val project: Project) {

    enum class BuildSystem { GRADLE, MAVEN, NODE, CARGO, PYTHON, UNKNOWN }

    /**
     * Returns the command to run after a write_code deed, or null if
     * validation should be skipped (no build system detected and no
     * custom command configured).
     *
     * [targetFile] is the relative path of the file just written — used
     * by the Python fallback to compile only the affected file.
     */
    fun getValidationCommand(basePath: String, targetFile: String?): String? {
        // User override always wins
        val custom = CelebrimbotSettingsState.getInstance(project).state.validationCommand.trim()
        if (custom.isNotEmpty()) return custom

        return when (detectBuildSystem(basePath)) {
            BuildSystem.GRADLE  -> "./gradlew classes"
            BuildSystem.MAVEN   -> "mvn compile -q"
            BuildSystem.NODE    -> "npm run build --silent"
            BuildSystem.CARGO   -> "cargo check"
            BuildSystem.PYTHON  -> if (targetFile != null) "python -m py_compile $targetFile" else null
            BuildSystem.UNKNOWN -> null
        }
    }

    fun detectBuildSystem(basePath: String): BuildSystem {
        val base = File(basePath)
        return when {
            File(base, "build.gradle.kts").exists() || File(base, "build.gradle").exists() -> BuildSystem.GRADLE
            File(base, "pom.xml").exists()                                                  -> BuildSystem.MAVEN
            File(base, "package.json").exists()                                             -> BuildSystem.NODE
            File(base, "Cargo.toml").exists()                                               -> BuildSystem.CARGO
            File(base, "pyproject.toml").exists() || File(base, "setup.py").exists()       -> BuildSystem.PYTHON
            else                                                                            -> BuildSystem.UNKNOWN
        }
    }

    companion object {
        fun getInstance(project: Project): ValidationService = project.service()
    }
}
