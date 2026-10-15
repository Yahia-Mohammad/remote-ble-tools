package dev.warsha.remoteble.tools.core

import dev.warsha.remoteble.protocol.AgentFingerprint
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets

/**
 * The WebSocket client for [endpoint]. Cleartext `ws://` keeps CIO on every target. `wss://` needs a
 * platform engine: CIO has no TLS on Kotlin/Native, and its JVM TLS client corrupts its own buffers
 * (upstream RemoteBLE 0.14.1 moved off it for that reason).
 *
 * With a [fingerprint], the agent is trusted exactly when its TLS key hashes to it: RemoteBLE 0.14+
 * agents serve `wss://` behind a self-signed identity that no certificate authority vouches for.
 * Without one, the platform's ordinary certificate verification applies, which suits an agent behind
 * a TLS proxy with a CA-issued certificate.
 */
internal fun agentHttpClient(endpoint: String, fingerprint: AgentFingerprint?): HttpClient =
    if (isEncryptedEndpoint(endpoint)) tlsAgentHttpClient(fingerprint) else HttpClient(CIO) { install(WebSockets) }

internal fun isEncryptedEndpoint(endpoint: String): Boolean = endpoint.trim().startsWith("wss://", ignoreCase = true)

internal expect fun tlsAgentHttpClient(fingerprint: AgentFingerprint?): HttpClient
