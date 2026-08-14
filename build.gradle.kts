plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

val remotebleVersion = providers.gradleProperty("remoteble.version").get()

allprojects {
    group = "dev.warsha.remoteble.tools"
    version = remotebleVersion

    repositories {
        mavenCentral()
    }
    dependencyLocking {
        lockAllConfigurations()
    }
}

tasks.register("formatCheck") {
    group = "verification"
    description = "Fails when tracked source files have trailing whitespace."
    doLast {
        val offenders = fileTree(projectDir) {
            include("**/*.kt", "**/*.kts", "**/*.md", "**/*.yaml", "**/*.yml", "**/*.json")
            exclude(".gradle/**", "build/**")
        }.filter { file -> file.readLines().any { line -> line.endsWith(' ') || line.endsWith('\t') } }
        check(offenders.isEmpty()) { "Trailing whitespace: ${offenders.joinToString { it.relativeTo(projectDir).path }}" }
    }
}

tasks.register("format") {
    group = "formatting"
    description = "Formatting entry point; run formatCheck in CI to enforce repository whitespace rules."
    dependsOn("formatCheck")
}

tasks.register("staticAnalysis") {
    group = "verification"
    description = "Static verification entry point."
    dependsOn("check")
}
