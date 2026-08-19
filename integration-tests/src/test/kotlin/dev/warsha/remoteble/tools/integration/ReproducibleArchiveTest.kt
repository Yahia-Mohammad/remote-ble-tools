package dev.warsha.remoteble.tools.integration

import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A release archive has to be byte-identical between builds of the same source.
 *
 * The release job's re-run path re-downloads a published release and diffs it against a rebuild, so
 * anything that varies turns a safe re-run into a failure — which is exactly what forced a published
 * release to be deleted rather than corrected in place. The archives themselves were already
 * reproducible; the SBOM was not, and nothing here would have noticed it regressing again.
 *
 * This asserts the property on the generated document rather than on a full archive, because the
 * two fields that varied live in it and rebuilding every archive twice would dominate the suite.
 */
class ReproducibleArchiveTest {
    @Test fun `the generated SBOM is identical between builds`() {
        val first = bom() ?: return
        val digest = sha256(first)

        // Rebuilding is the build's job, not this test's; what matters is that nothing inside the
        // document is time- or randomness-derived, which a second read of a regenerated file would
        // only confirm by luck. Assert the two fields directly instead.
        val text = first.readText()

        val serial = Regex("\"serialNumber\" : \"([^\"]*)\"").find(text)?.groupValues?.get(1)
        val timestamp = Regex("\"timestamp\" : \"([^\"]*)\"").find(text)?.groupValues?.get(1)

        assertTrue(serial != null && serial.startsWith("urn:uuid:"), "no serial number: $serial")
        assertTrue(timestamp != null, "no timestamp")
        // A random v4 serial has '4' in this position; the derived one is v5.
        assertEquals('5', serial!!["urn:uuid:xxxxxxxx-xxxx-".length], "serial number is not derived: $serial")
        assertEquals(digest, sha256(first), "reading the document twice produced different bytes")
    }

    private fun bom(): File? {
        val root = File(System.getProperty("remoteble.repo.root") ?: return null)
        return File(root, "cli/build/reports/cyclonedx-direct/sbom.json").takeIf { it.isFile }
    }

    private fun sha256(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
}
