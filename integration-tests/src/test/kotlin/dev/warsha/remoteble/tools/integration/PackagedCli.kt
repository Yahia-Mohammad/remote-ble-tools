package dev.warsha.remoteble.tools.integration

import java.io.File
import java.nio.file.Path

/**
 * Locates the packaged fat JAR under test. The path comes from the `remoteble.cli.libs` system
 * property set by the `test` task rather than from `user.dir`, which is this module's directory and
 * not the repository root.
 */
internal object PackagedCli {
    fun launcher(): Path {
        val libraries = System.getProperty("remoteble.cli.libs")
            ?: error("remoteble.cli.libs is not set; run these tests through Gradle")
        val name = System.getProperty("remoteble.cli.jar")
            ?: error("remoteble.cli.jar is not set; run these tests through Gradle")
        val jar = File(libraries, name).takeIf(File::isFile)
            ?: error("packaged CLI is missing: ${File(libraries, name)}; run :cli:fatJar")
        return jar.toPath()
    }
}
