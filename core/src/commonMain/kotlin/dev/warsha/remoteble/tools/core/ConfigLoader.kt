package dev.warsha.remoteble.tools.core

import com.charleskorn.kaml.Yaml
import kotlinx.serialization.SerializationException
import kotlin.time.Duration

object ConfigLoader {
    fun defaultPath(): String = homeDirectory().trimEnd('/') + "/.config/remoteble/config.yaml"

    fun load(explicitPath: String? = null, profile: String? = null): Pair<FileConfig, String?> {
        val selected = explicitPath
            ?: environmentVariable("REMOTE_BLE_CONFIG")
            ?: defaultPath()
        if (!fileExists(selected)) return FileConfig().withProfile(profile) to null
        val config = try {
            Yaml.default.decodeFromString(FileConfig.serializer(), readFileText(selected))
        } catch (error: SerializationException) {
            throw CliFailure(ExitCode.USAGE, "Invalid configuration at $selected: ${error.message}", error)
        } catch (error: IllegalArgumentException) {
            throw CliFailure(ExitCode.USAGE, "Invalid configuration at $selected: ${error.message}", error)
        }
        val selectedConfig = config.withProfile(profile)
        validate(selectedConfig, selected)
        return selectedConfig to selected
    }

    fun validate(config: FileConfig, path: String? = null) {
        if (config.schemaVersion != 1) throw CliFailure(ExitCode.USAGE, "Unsupported config schema version ${config.schemaVersion}")
        if (config.agent.endpoint.isBlank()) throw CliFailure(ExitCode.USAGE, "agent.endpoint must not be blank")
        listOf("defaults.scanDuration" to config.defaults.scanDuration, "defaults.operationTimeout" to config.defaults.operationTimeout)
            .forEach { (name, value) ->
                runCatching { kotlin.time.Duration.parse(value) }.getOrElse {
                    throw CliFailure(ExitCode.USAGE, "$name must be an ISO-8601 or Kotlin duration, got '$value'${path?.let { " in $it" }.orEmpty()}")
                }
            }
        checkConfig(config.policy.maximumWriteBytes > 0, "policy.maximumWriteBytes must be positive")
        checkConfig(config.policy.maximumNotificationCount > 0, "policy.maximumNotificationCount must be positive")
        checkConfig(config.policy.maximumWritesPerWindow > 0, "policy.maximumWritesPerWindow must be positive")
        // Parsing is not enough: `0s` and negative durations parse, and either one prunes every
        // previous entry from the ledger, which silently turns the write limiter off.
        val window = runCatching { Duration.parse(config.policy.writeRateWindow) }.getOrElse {
            throw CliFailure(ExitCode.USAGE, "policy.writeRateWindow must be a duration")
        }
        checkConfig(window.isPositive() && window.isFinite(), "policy.writeRateWindow must be a positive, finite duration")
        config.policy.writeRules.forEachIndexed { index, rule ->
            val prefix = "policy.writeRules[$index]"
            checkConfig(rule.endpoint.isNotBlank(), "$prefix.endpoint must not be blank")
            checkConfig(rule.device.isNotBlank(), "$prefix.device must not be blank")
            checkConfig(rule.serviceUuid.isNotBlank(), "$prefix.serviceUuid must not be blank")
            checkConfig(rule.characteristicUuid.isNotBlank(), "$prefix.characteristicUuid must not be blank")
            runCatching { normalizeUuid(rule.serviceUuid) }.getOrElse { throw CliFailure(ExitCode.USAGE, "$prefix.serviceUuid is not a valid UUID") }
            runCatching { normalizeUuid(rule.characteristicUuid) }.getOrElse { throw CliFailure(ExitCode.USAGE, "$prefix.characteristicUuid is not a valid UUID") }
            checkConfig(rule.maximumBytes in 0..512, "$prefix.maximumBytes must be between 0 and 512")
            checkConfig(rule.withResponse.isNotEmpty(), "$prefix.withResponse must not be empty")
        }
    }

    private fun checkConfig(condition: Boolean, message: String) {
        if (!condition) throw CliFailure(ExitCode.USAGE, message)
    }
}
