package dev.warsha.remoteble.tools.core

import kotlin.time.Duration
import kotlinx.serialization.Serializable

@Serializable
data class FileConfig(
    val schemaVersion: Int = 1,
    val agent: AgentConfig = AgentConfig(),
    val defaults: DefaultsConfig = DefaultsConfig(),
    val policy: PolicyConfig = PolicyConfig(),
    val profiles: Map<String, ProfileConfig> = emptyMap(),
)

@Serializable
data class ProfileConfig(
    val agent: AgentProfileConfig? = null,
    val defaults: DefaultsProfileConfig? = null,
    val policy: PolicyProfileConfig? = null,
)

@Serializable
data class AgentProfileConfig(
    val endpoint: String? = null,
    val tokenEnvironmentVariable: String? = null,
    val clientId: String? = null,
    val operatorTokenEnvironmentVariable: String? = null,
)

@Serializable
data class DefaultsProfileConfig(val scanDuration: String? = null, val operationTimeout: String? = null, val output: String? = null, val logLevel: String? = null, val logDirectory: String? = null)

@Serializable
data class PolicyProfileConfig(
    val readOnly: Boolean? = null,
    val maximumWriteBytes: Int? = null,
    val maximumNotificationCount: Int? = null,
    val allowUnboundedStreams: Boolean? = null,
    val writeRules: List<WriteRuleConfig>? = null,
    val maximumWritesPerWindow: Int? = null,
    val writeRateWindow: String? = null,
)

@Serializable
data class AgentConfig(
    val endpoint: String = "ws://127.0.0.1:8080/agent",
    val tokenEnvironmentVariable: String = "REMOTE_BLE_TOKEN",
    val clientId: String? = null,
    val operatorTokenEnvironmentVariable: String? = null,
)

@Serializable
data class DefaultsConfig(
    val scanDuration: String = "5s",
    val operationTimeout: String = "20s",
    val output: String = "human",
    val logLevel: String = "audit",
    val logDirectory: String? = null,
)

@Serializable
data class PolicyConfig(
    val readOnly: Boolean = true,
    val maximumWriteBytes: Int = 64,
    val maximumNotificationCount: Int = 1000,
    val allowUnboundedStreams: Boolean = false,
    val writeRules: List<WriteRuleConfig> = emptyList(),
    val maximumWritesPerWindow: Int = 60,
    val writeRateWindow: String = "60s",
)

/** A local advisory allowlist entry. Every routing value is exact; v0.1 has no wildcards. */
@Serializable
data class WriteRuleConfig(
    val endpoint: String,
    val device: String,
    val serviceUuid: String,
    val characteristicUuid: String,
    val maximumBytes: Int,
    val withResponse: Set<Boolean>,
)

data class ResolvedConfig(
    val agent: AgentConfig,
    val scanDuration: Duration,
    val operationTimeout: Duration,
    val policy: PolicyConfig,
)

fun FileConfig.withProfile(name: String?): FileConfig {
    if (name == null) return this
    val profile = profiles[name] ?: throw CliFailure(ExitCode.USAGE, "Unknown configuration profile '$name'")
    return copy(
        agent = profile.agent?.mergeOnto(agent) ?: agent,
        defaults = profile.defaults?.mergeOnto(defaults) ?: defaults,
        policy = profile.policy?.mergeOnto(policy) ?: policy,
    )
}

private fun AgentProfileConfig.mergeOnto(base: AgentConfig) = AgentConfig(
    endpoint = endpoint ?: base.endpoint,
    tokenEnvironmentVariable = tokenEnvironmentVariable ?: base.tokenEnvironmentVariable,
    clientId = clientId ?: base.clientId,
    operatorTokenEnvironmentVariable = operatorTokenEnvironmentVariable ?: base.operatorTokenEnvironmentVariable,
)

private fun DefaultsProfileConfig.mergeOnto(base: DefaultsConfig) = DefaultsConfig(
    scanDuration = scanDuration ?: base.scanDuration,
    operationTimeout = operationTimeout ?: base.operationTimeout,
    output = output ?: base.output,
    logLevel = logLevel ?: base.logLevel,
    logDirectory = logDirectory ?: base.logDirectory,
)

private fun PolicyProfileConfig.mergeOnto(base: PolicyConfig) = PolicyConfig(
    readOnly = readOnly ?: base.readOnly,
    maximumWriteBytes = maximumWriteBytes ?: base.maximumWriteBytes,
    maximumNotificationCount = maximumNotificationCount ?: base.maximumNotificationCount,
    allowUnboundedStreams = allowUnboundedStreams ?: base.allowUnboundedStreams,
    writeRules = writeRules ?: base.writeRules,
    maximumWritesPerWindow = maximumWritesPerWindow ?: base.maximumWritesPerWindow,
    writeRateWindow = writeRateWindow ?: base.writeRateWindow,
)

fun FileConfig.resolve(endpointOverride: String? = null, clientIdOverride: String? = null): ResolvedConfig =
    ResolvedConfig(
        agent = agent.copy(endpoint = endpointOverride ?: agent.endpoint, clientId = (clientIdOverride ?: agent.clientId)?.let(::validateClientIdentity)),
        scanDuration = Duration.parse(defaults.scanDuration),
        operationTimeout = Duration.parse(defaults.operationTimeout),
        policy = policy,
    )

fun PolicyConfig.allowsWrite(
    endpoint: String,
    device: String,
    serviceUuid: String,
    characteristicUuid: String,
    payloadBytes: Int,
    withResponse: Boolean,
): Boolean {
    if (payloadBytes > maximumWriteBytes) return false
    // Enabling writes is deliberately not an allow-all switch. An empty local allowlist is a
    // configuration mistake that must not reach the radio.
    if (writeRules.isEmpty()) return false
    return writeRules.any { rule ->
        rule.endpoint == endpoint &&
            rule.device == device &&
            runCatching { normalizeUuid(rule.serviceUuid) == normalizeUuid(serviceUuid) }.getOrDefault(false) &&
            runCatching { normalizeUuid(rule.characteristicUuid) == normalizeUuid(characteristicUuid) }.getOrDefault(false) &&
            payloadBytes <= rule.maximumBytes &&
            withResponse in rule.withResponse
    }
}
