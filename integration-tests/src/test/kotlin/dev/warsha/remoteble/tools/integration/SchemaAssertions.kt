package dev.warsha.remoteble.tools.integration

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertTrue

/** Validates examples against the versioned JSON Schema files actually shipped by this repository. */
internal object SchemaAssertions {
    private val registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
    private val root: Path = Path.of(checkNotNull(System.getProperty("remoteble.repo.root")) {
        "remoteble.repo.root is not set; run the integration tests through Gradle"
    })

    fun assertValid(schema: String, document: String) {
        val errors = registry
            .getSchema(Files.readString(root.resolve("schemas").resolve(schema)), InputFormat.JSON)
            .validate(document, InputFormat.JSON)
        assertTrue(errors.isEmpty(), "${schema} rejected:\n$document\n${errors.joinToString("\n")}")
    }

    fun assertInvalid(schema: String, document: String) {
        val errors = registry
            .getSchema(Files.readString(root.resolve("schemas").resolve(schema)), InputFormat.JSON)
            .validate(document, InputFormat.JSON)
        assertTrue(errors.isNotEmpty(), "${schema} unexpectedly accepted:\n$document")
    }
}
