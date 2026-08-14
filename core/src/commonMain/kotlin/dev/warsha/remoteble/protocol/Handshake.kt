package dev.warsha.remoteble.protocol

/**
 * HTTP header carrying the client's **stable session id** on the WebSocket handshake.
 *
 * Unlike the per-connection id the agent mints for monitoring, this value is generated once
 * per client transport and re-sent on every reconnect, so the agent can recognise a returning
 * client after a brief link drop and let it **resume** its peripheral ownership (rather than
 * treating the new socket as a different client). It is not an auth credential — that is the
 * separate bearer token.
 */
const val CLIENT_ID_HEADER: String = "X-RemoteBle-Client"

/**
 * Optional bearer credential that widens the management view of an already authenticated session.
 * It is deliberately separate from [CLIENT_ID_HEADER] and the ordinary Authorization bearer: an
 * operator still acts as the ordinary principal for BLE authorization.
 */
const val OPERATOR_HEADER: String = "X-RemoteBle-Operator"
