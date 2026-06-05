import java.util.EnumSet
import org.jetbrains.changelog.Changelog
import org.jetbrains.changelog.markdownToHTML
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java") // Java support
    alias(libs.plugins.kotlin) // Kotlin support
    alias(libs.plugins.intelliJPlatform) // IntelliJ Platform Gradle Plugin
    alias(libs.plugins.changelog) // Gradle Changelog Plugin
    alias(libs.plugins.qodana) // Gradle Qodana Plugin
    alias(libs.plugins.kover) // Gradle Kover Plugin
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

// Set the JVM language level used to build the project.
kotlin {
    jvmToolchain(21)
}

// Configure project's dependencies
repositories {
    mavenCentral()

    // IntelliJ Platform Gradle Plugin Repositories Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-repositories-extension.html
    intellijPlatform {
        defaultRepositories()
    }
}

// Force Netty to a patched version to resolve CVEs in the transitive dependency
// pulled in by ktor-server-netty.
// Highest patched version required: 4.1.132.Final (CVE-2026-33871, CVE-2026-33870)
// All modules pinned to the same version for consistency.
configurations.all {
    resolutionStrategy.force(
        "io.netty:netty-codec-http2:4.1.132.Final",
        "io.netty:netty-codec-http:4.1.132.Final",
        "io.netty:netty-handler:4.1.132.Final",
        "io.netty:netty-codec:4.1.132.Final",
        "io.netty:netty-common:4.1.132.Final",
        "io.netty:netty-transport:4.1.132.Final",
        "io.netty:netty-buffer:4.1.132.Final"
    )
}

// Dependencies are managed with Gradle version catalog - read more: https://docs.gradle.org/current/userguide/version_catalogs.html
val cliOnly by configurations.creating {
    extendsFrom(configurations.compileOnly.get())
}

dependencies {
    implementation(libs.gson)
    implementation(libs.llama) {
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core-jvm")
    }

    compileOnly(libs.clikt)
    compileOnly(libs.ktor.server.core)
    compileOnly(libs.ktor.server.netty)
    compileOnly(libs.ktor.server.content.negotiation)
    compileOnly(libs.ktor.serialization.gson)

    cliOnly(libs.clikt)
    cliOnly(libs.ktor.server.core)
    cliOnly(libs.ktor.server.netty)
    cliOnly(libs.ktor.server.content.negotiation)
    cliOnly(libs.ktor.serialization.gson)
    cliOnly(libs.gson)
    cliOnly(libs.llama) {
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core-jvm")
    }
    
    testImplementation(libs.junit)
    testImplementation(libs.opentest4j)
    testImplementation("io.mockk:mockk:1.13.13") {
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core-jvm")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-test")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-test-jvm")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-jdk8")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-debug")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-bom")
    }
    testImplementation("io.kotest:kotest-property:5.9.1") {
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core-jvm")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-test")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-test-jvm")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-jdk8")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-debug")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-bom")
    }
    testImplementation("io.kotest:kotest-runner-junit5:5.9.1") {
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core-jvm")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-test")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-test-jvm")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-jdk8")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-debug")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-bom")
    }
    // Kotest needs coroutines-test for TestDispatcher. Add it directly but exclude
    // kotlinx-coroutines-core so the IntelliJ platform's patched version is used.
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1") {
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core-jvm")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-bom")
    }
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.10.2")

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        intellijIdea(providers.gradleProperty("platformVersion"))

        // Plugin Dependencies. Uses `platformBundledPlugins` property from the gradle.properties file for bundled IntelliJ Platform plugins.
        bundledPlugins(providers.gradleProperty("platformBundledPlugins").map { it.split(',') })

        // Plugin Dependencies. Uses `platformPlugins` property from the gradle.properties file for plugin from JetBrains Marketplace.
        plugins(providers.gradleProperty("platformPlugins").map { it.split(',') })

        // Module Dependencies. Uses `platformBundledModules` property from the gradle.properties file for bundled IntelliJ Platform modules.
        bundledModules(providers.gradleProperty("platformBundledModules").map { it.split(',') })

        testFramework(TestFrameworkType.Platform)
    }
}

// Configure IntelliJ Platform Gradle Plugin - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-extension.html
intellijPlatform {
    pluginConfiguration {
        name = providers.gradleProperty("pluginName")
        version = providers.gradleProperty("pluginVersion")

        // Extract the <!-- Plugin description --> section from README.md and provide for the plugin's manifest
        description = providers.fileContents(layout.projectDirectory.file("README.md")).asText.map {
            val start = "<!-- Plugin description -->"
            val end = "<!-- Plugin description end -->"

            with(it.lines()) {
                if (!containsAll(listOf(start, end))) {
                    throw GradleException("Plugin description section not found in README.md:\n$start ... $end")
                }
                subList(indexOf(start) + 1, indexOf(end)).joinToString("\n").let(::markdownToHTML)
            }
        }

        val changelog = project.changelog // local variable for configuration cache compatibility
        // Get the latest available change notes from the changelog file
        changeNotes = providers.gradleProperty("pluginVersion").map { pluginVersion ->
            with(changelog) {
                renderItem(
                    (getOrNull(pluginVersion) ?: getUnreleased())
                        .withHeader(false)
                        .withEmptySections(false),
                    Changelog.OutputType.HTML,
                )
            }
        }

        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
        }
    }

    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
        // The pluginVersion is based on the SemVer (https://semver.org) and supports pre-release labels, like 2.1.7-alpha.3
        // Specify pre-release label to publish the plugin in a custom Release Channel automatically. Read more:
        // https://plugins.jetbrains.com/docs/intellij/publishing-plugin.html#specifying-a-release-channel
        channels = providers.gradleProperty("pluginVersion").map { listOf(it.substringAfter('-', "").substringBefore('.').ifEmpty { "default" }) }
    }

    pluginVerification {
        ides {
            recommended()
        }
        // ToolWindowFactory.manage/getAnchor/getIcon are marked @ApiStatus.Internal and
        // @ApiStatus.Experimental in the platform SDK. Our class inherits them from the
        // interface without overriding, but the verifier still flags the usage.
        // isApplicable/isDoNotActivateOnStart are deprecated but still inherited.
        // All COMPATIBILITY_PROBLEMS are exclusively from CLI-only code (Ktor/Clikt)
        // that references compileOnly deps absent from the plugin ZIP.
        // Exclude these non-actionable failure levels.
        failureLevel.set(
            EnumSet.complementOf(
                EnumSet.of(
                    org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel.EXPERIMENTAL_API_USAGES,
                    org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel.INTERNAL_API_USAGES,
                    org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel.DEPRECATED_API_USAGES,
                    org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel.COMPATIBILITY_PROBLEMS
                )
            )
        )
        // Clikt and Ktor are CLI-only dependencies bundled exclusively in the Shadow JAR.
        // They are declared compileOnly so they are absent from the plugin ZIP — the verifier
        // correctly detects them as unresolved, but they will never be loaded by the IDE.
        // externalPrefixes tells the verifier to skip "No such class" for these packages.
        externalPrefixes.addAll(
            "com.github.ajalt.clikt",
            "io.ktor"
        )
    }
}

// Configure Gradle Changelog Plugin - read more: https://github.com/JetBrains/gradle-changelog-plugin
changelog {
    groups.empty()
    repositoryUrl = providers.gradleProperty("pluginRepositoryUrl")
    versionPrefix = ""
}

// Configure Gradle Kover Plugin - read more: https://kotlin.github.io/kotlinx-kover/gradle-plugin/#configuration-details
kover {
    reports {
        total {
            xml {
                onCheck = true
            }
        }
    }
}

tasks {
    test {
        useJUnitPlatform()
    }

    // Exclude CLI-only and eval classes from the plugin JAR.
    // These reference Clikt/Ktor (compileOnly) and would cause false-positive
    // "unresolved method" compatibility problems in the verifier.
    // The IntelliJ Platform Plugin tasks (InstrumentedJarTask, ComposedJarTask)
    // don't support standard Jar excludes, so we exclude from the base jar only.
    // The remaining unresolved-method warnings are suppressed via failureLevel
    // since all COMPATIBILITY_PROBLEMS are exclusively Ktor/CLI code that never
    // loads in the IDE.
    named<Jar>("jar") {
        exclude("com/github/giacomosaccaggi/celebrimbot/cli/**")
        exclude("com/github/giacomosaccaggi/celebrimbot/eval/**")
    }

    wrapper {
        gradleVersion = providers.gradleProperty("gradleVersion").get()
    }

    publishPlugin {
        dependsOn(patchChangelog)
    }

    shadowJar {
        configurations = listOf(project.configurations.getByName("cliOnly"))
        exclude("kotlinx/coroutines/**")
        exclude("kotlin/coroutines/jvm/internal/DebugProbes*")
        archiveFileName.set("celebrimbot.jar")
        manifest {
            attributes["Main-Class"] = "com.github.giacomosaccaggi.celebrimbot.cli.CelebrimbotCLIKt"
        }
    }
}

intellijPlatformTesting {
    runIde {
        register("runIdeForUiTests") {
            task {
                jvmArgumentProviders += CommandLineArgumentProvider {
                    listOf(
                        "-Drobot-server.port=8082",
                        "-Dide.mac.message.dialogs.as.sheets=false",
                        "-Djb.privacy.policy.text=<!--999.999-->",
                        "-Djb.consents.confirmation.enabled=false",
                    )
                }
            }

            plugins {
                robotServerPlugin()
            }
        }
    }
}
