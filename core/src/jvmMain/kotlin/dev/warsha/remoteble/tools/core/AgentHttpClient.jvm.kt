package dev.warsha.remoteble.tools.core

import dev.warsha.remoteble.client.PinningTrustManager
import dev.warsha.remoteble.protocol.AgentFingerprint
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * OkHttp. When pinned, the trust manager accepts exactly the pinned key, and the host-name check
 * defers to the pin as well, since agents are reached by IP addresses their certificates cannot
 * name. It checks the session's certificate again rather than passing everything, because a
 * resumed session skips the trust manager.
 */
internal actual fun tlsAgentHttpClient(fingerprint: AgentFingerprint?): HttpClient = HttpClient(OkHttp) {
    if (fingerprint != null) {
        engine {
            config {
                val trustManager = PinningTrustManager(fingerprint)
                val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }
                sslSocketFactory(context.socketFactory, trustManager)
                hostnameVerifier { _, session ->
                    val leaf = try {
                        session.peerCertificates.firstOrNull()
                    } catch (_: SSLPeerUnverifiedException) {
                        null
                    }
                    trustManager.matches(leaf)
                }
            }
        }
    }
    install(WebSockets)
}
