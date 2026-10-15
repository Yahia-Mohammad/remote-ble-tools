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

    /**
     * Resolves the dependency graph of every resolvable configuration. With `--write-locks` this
     * regenerates every lockfile from the version catalog, which is how CI checks them: it fails
     * when the regenerated files differ from the committed ones.
     *
     * Resolving without `--write-locks` is no check at all: in the default lock mode a stale lock
     * wins over the catalog, so a version bump that was never relocked still resolves, at the old
     * version. `dependencies` is no substitute either: it runs per project, prints resolution
     * failures as text, and succeeds.
     *
     * Configurations a host cannot resolve (macOS-only native targets on Linux, say) keep the state
     * another host wrote for them.
     */
    tasks.register("resolveDependencies") {
        group = "verification"
        description = "Resolves every configuration; with --write-locks, regenerates every lockfile."
        val resolvable = configurations.matching { configuration ->
            // Kotlin's commonizer reads these through its own artifact views; resolved directly they
            // fail variant selection on every host, lock or no lock.
            configuration.isCanBeResolved &&
                configuration.description?.startsWith("Commonized CInterop dependencies") != true
        }
        val projectPath = path
        doLast {
            val failures = resolvable.flatMap { configuration ->
                val failure = runCatching {
                    configuration.incoming.resolutionResult.allDependencies
                        .filterIsInstance<org.gradle.api.artifacts.result.UnresolvedDependencyResult>()
                        .map { "${it.attempted.displayName}: ${it.failure.message?.lineSequence()?.first()}" }
                }
                failure.getOrElse { listOf(it.message.orEmpty()) }.map { "$projectPath:${configuration.name} -> $it" }
            }
            check(failures.isEmpty()) { "Unresolved dependencies:\n" + failures.joinToString("\n") { "  - $it" } }
        }
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
