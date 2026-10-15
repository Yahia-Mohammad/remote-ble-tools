package dev.warsha.remoteble.tools.core

import dev.warsha.remoteble.client.pinnedWebSocketHttpClient
import dev.warsha.remoteble.protocol.AgentFingerprint
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.websocket.WebSockets

/** Darwin (NSURLSession), pinned by its challenge handler when a fingerprint is configured. */
internal actual fun tlsAgentHttpClient(fingerprint: AgentFingerprint?): HttpClient =
    fingerprint?.let(::pinnedWebSocketHttpClient) ?: HttpClient(Darwin) { install(WebSockets) }
