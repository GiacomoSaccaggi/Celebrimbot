plugins {
    alias(libs.plugins.kotlin)
    id("com.gradleup.shadow") version "9.0.0-beta4"
}

kotlin {
    jvmToolchain(21)
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":core"))
    implementation(kotlin("stdlib"))
    implementation(libs.clikt)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.gson)
    implementation(libs.gson)
    implementation(libs.llama) {
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core-jvm")
    }

    testImplementation(libs.junit)
    testImplementation(libs.opentest4j)
}

tasks {
    test {
        useJUnitPlatform()
    }

    shadowJar {
        exclude("META-INF/*.SF")
        exclude("META-INF/*.DSA")
        exclude("META-INF/*.RSA")
        exclude("META-INF/MANIFEST.MF")
        mergeServiceFiles()
        archiveFileName.set("celebrimbot.jar")
        manifest {
            attributes["Main-Class"] = "com.github.giacomosaccaggi.celebrimbot.cli.CelebrimbotCLIKt"
        }
    }
}
