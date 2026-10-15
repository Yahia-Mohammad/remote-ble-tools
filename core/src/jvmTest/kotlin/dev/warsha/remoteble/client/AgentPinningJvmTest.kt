package dev.warsha.remoteble.client

import dev.warsha.remoteble.protocol.AgentFingerprint
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Pins against a real RemoteBLE 0.15.0 agent certificate (public part only), whose fingerprint the
 * agent printed as [AGENT_FINGERPRINT]. The JVM is the one platform that can state the expected
 * SPKI bytes, so the DER walk the macOS client pins with is checked here.
 */
class AgentPinningJvmTest {
    private val certificate = checkNotNull(javaClass.getResourceAsStream("/agent-certificate.pem")).use {
        CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
    }

    @Test
    fun theDerWalkFindsTheSameSpkiAsTheJdk() {
        assertContentEquals(certificate.publicKey.encoded, certificateSpki(certificate.encoded))
    }

    @Test
    fun theSpkiHashesToTheFingerprintTheAgentPrinted() {
        val digest = MessageDigest.getInstance("SHA-256").digest(certificateSpki(certificate.encoded)!!)
        assertEquals(AGENT_FINGERPRINT, AgentFingerprint.ofSpkiSha256(digest).toString())
    }

    @Test
    fun aDamagedCertificateYieldsNothing() {
        val der = certificate.encoded
        assertNull(certificateSpki(der.copyOf(der.size / 2)))
        assertNull(certificateSpki(byteArrayOf()))
        assertNull(certificateSpki(der.copyOf().also { it[0] = 0x31 }))
    }

    @Test
    fun theTrustManagerAcceptsOnlyThePinnedKey() {
        PinningTrustManager(AgentFingerprint.parse(AGENT_FINGERPRINT)).checkServerTrusted(arrayOf(certificate), "ECDHE_ECDSA")
        val other = AgentFingerprint.parse("sha256:" + "0".repeat(64))
        val refusal = assertFailsWith<CertificateException> {
            PinningTrustManager(other).checkServerTrusted(arrayOf(certificate), "ECDHE_ECDSA")
        }
        val mismatch = assertIs<AgentIdentityMismatchException>(refusal.cause)
        assertEquals(other, mismatch.expected)
        assertEquals(AGENT_FINGERPRINT, mismatch.presented.toString())
        assertIs<AgentIdentityMismatchException>(RuntimeException(refusal).asTerminalConnectFailure())
    }

    private companion object {
        const val AGENT_FINGERPRINT = "sha256:8bddc8daceca6b001bd79b40cea6d53df1a570711087b25f426c9b711a2b98a4"
    }
}
