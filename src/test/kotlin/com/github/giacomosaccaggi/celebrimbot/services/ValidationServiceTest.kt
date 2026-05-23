package com.github.giacomosaccaggi.celebrimbot.services

import com.intellij.openapi.project.Project
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ValidationServiceTest {

    @get:Rule
    val tmpDir = TemporaryFolder()

    private val project = mockk<Project>(relaxed = true)
    private val service = ValidationService(project)

    @Test
    fun `detectBuildSystem returns UNKNOWN for empty directory`() {
        val dir = tmpDir.newFolder("empty")
        assertEquals(ValidationService.BuildSystem.UNKNOWN, service.detectBuildSystem(dir.absolutePath))
    }

    @Test
    fun `detectBuildSystem returns GRADLE for build_gradle_kts`() {
        val dir = tmpDir.newFolder("gradle-kts")
        dir.resolve("build.gradle.kts").createNewFile()
        assertEquals(ValidationService.BuildSystem.GRADLE, service.detectBuildSystem(dir.absolutePath))
    }

    @Test
    fun `detectBuildSystem returns GRADLE for build_gradle`() {
        val dir = tmpDir.newFolder("gradle-groovy")
        dir.resolve("build.gradle").createNewFile()
        assertEquals(ValidationService.BuildSystem.GRADLE, service.detectBuildSystem(dir.absolutePath))
    }

    @Test
    fun `detectBuildSystem returns MAVEN for pom_xml`() {
        val dir = tmpDir.newFolder("maven")
        dir.resolve("pom.xml").createNewFile()
        assertEquals(ValidationService.BuildSystem.MAVEN, service.detectBuildSystem(dir.absolutePath))
    }

    @Test
    fun `detectBuildSystem returns NODE for package_json`() {
        val dir = tmpDir.newFolder("node")
        dir.resolve("package.json").createNewFile()
        assertEquals(ValidationService.BuildSystem.NODE, service.detectBuildSystem(dir.absolutePath))
    }

    @Test
    fun `detectBuildSystem returns CARGO for Cargo_toml`() {
        val dir = tmpDir.newFolder("cargo")
        dir.resolve("Cargo.toml").createNewFile()
        assertEquals(ValidationService.BuildSystem.CARGO, service.detectBuildSystem(dir.absolutePath))
    }

    @Test
    fun `detectBuildSystem returns PYTHON for pyproject_toml`() {
        val dir = tmpDir.newFolder("python")
        dir.resolve("pyproject.toml").createNewFile()
        assertEquals(ValidationService.BuildSystem.PYTHON, service.detectBuildSystem(dir.absolutePath))
    }

    @Test
    fun `detectBuildSystem returns PYTHON for setup_py`() {
        val dir = tmpDir.newFolder("python-setup")
        dir.resolve("setup.py").createNewFile()
        assertEquals(ValidationService.BuildSystem.PYTHON, service.detectBuildSystem(dir.absolutePath))
    }

    @Test
    fun `detectBuildSystem prioritizes GRADLE over MAVEN when both present`() {
        val dir = tmpDir.newFolder("multi")
        dir.resolve("build.gradle.kts").createNewFile()
        dir.resolve("pom.xml").createNewFile()
        assertEquals(ValidationService.BuildSystem.GRADLE, service.detectBuildSystem(dir.absolutePath))
    }
}
