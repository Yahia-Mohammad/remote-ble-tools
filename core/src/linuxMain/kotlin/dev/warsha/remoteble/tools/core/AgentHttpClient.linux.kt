package dev.warsha.remoteble.tools.core

import dev.warsha.remoteble.protocol.AgentFingerprint
import io.ktor.client.HttpClient

/**
 * Ktor has no Linux engine that can both speak WebSocket over TLS and pin a key: CIO has no TLS on
 * Kotlin/Native, and the Curl engine exposes no `CURLOPT_PINNEDPUBLICKEY`. Refuse rather than
 * connect unverified.
 */
internal actual fun tlsAgentHttpClient(fingerprint: AgentFingerprint?): HttpClient =
    throw CliFailure(
        ExitCode.UNSUPPORTED,
        "The Linux native build cannot open wss:// connections. Use the JVM distribution " +
            "(remoteble-<version>-all.jar), which supports wss:// and agent.fingerprint, or a ws:// endpoint.",
    )
