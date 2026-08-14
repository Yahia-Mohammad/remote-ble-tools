package dev.warsha.remoteble.tools.core

import dev.warsha.remoteble.protocol.Capabilities

/** CLI features whose upstream capability names are deliberately mapped in one place. */
enum class AgentFeature(val capability: String?) {
    DESCRIPTORS(Capabilities.DESCRIPTORS),
    RSSI(Capabilities.RSSI),
    STATUS(Capabilities.AGENT_STATUS),
    GLOBAL_SLOTS(Capabilities.CONNECTION_SLOTS),
    WRITE_POLICY(Capabilities.WRITE_POLICY),
    HOLDER_DIAGNOSTICS(Capabilities.LEASE_HOLDER),
}

fun Set<String>.supports(feature: AgentFeature): Boolean =
    feature.capability?.let(::contains) ?: false
