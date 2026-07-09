plugins {
    alias(libs.plugins.kotlin) apply false
    alias(libs.plugins.intelliJPlatform) apply false
    alias(libs.plugins.changelog) apply false
    alias(libs.plugins.qodana) apply false
    alias(libs.plugins.kover) apply false
}

// Force Netty to a patched version to resolve CVEs in the transitive dependency
// pulled in by ktor-server-netty.
// Highest patched version required: 4.1.132.Final (CVE-2026-33871, CVE-2026-33870)
subprojects {
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
}
