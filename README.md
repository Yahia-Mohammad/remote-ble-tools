# remoteble-tools

A command-line client and companion Agent Skill for
[RemoteBLE](https://github.com/Yahia-Mohammad/remote-ble).

Primary executable: `remoteble` (optional short alias `rble`).

> Control and debug BLE devices anywhere using commands, pipes, and AI coding agents.

## What this is

A CLI that turns the RemoteBLE SDK into a general-purpose developer tool, exposing the remote BLE
control plane independently of Kable application code, in any environment able to run a command.

```text
Human / script / CI / coding agent
              │ commands
        remoteble CLI          parsing · output · advisory policy
              │
      slim protocol client
              │ WebSocket + CBOR
       RemoteBLE agent         auth · leases · reconciliation · radio
              │
          BLE device
```

The defining property is that the command may run anywhere while the radio runs beside the
physical device:

```text
Local BLE CLI:      command → local adapter → device
RemoteBLE CLI:      command → network → chosen RemoteBLE agent → device
```

That enables remote labs, phones as radio nodes, CI access from radio-less runners, shared-device
leasing, and simulation.

A companion Agent Skill adds procedural knowledge for coding agents without making AI part of the
core product. The CLI is independently useful, testable, and scriptable.

## Build and run

Build a self-contained native executable on Apple Silicon:

```sh
./gradlew :cli:linkRemotebleReleaseExecutableMacosArm64
cli/build/bin/macosArm64/remotebleReleaseExecutable/remoteble.kexe --help
```

The same Kotlin Multiplatform source also links Linux x64 and ARM64 executables with
`:cli:linkRemotebleReleaseExecutableLinuxX64` and
`:cli:linkRemotebleReleaseExecutableLinuxArm64`. `:cli:fatJar` provides a JVM compatibility
artifact; the native executable is the primary distribution and requires no installed JRE.

Release archives include a manual page in `dist/man/remoteble.1` and Bash, Fish, and Zsh completion
assets in `dist/completions/`.

Every archive also includes the human-readable Agent Skill at `dist/skills/remoteble`. Install the
embedded copy without MCP or credentials with `remoteble skills install`; use
`remoteble skills doctor` to verify installed copies.

The distribution also includes `rble`, a short launcher alias. Use [examples/config.yaml](examples/config.yaml)
as a starting configuration and set the agent token in its configured environment variable.
Configuration precedence is command-line options, selected profile, configuration file, then built-in defaults.

Structured output uses the versioned [result envelope schema](schemas/result-envelope-v1.json) and the
[persistent session schemas](schemas/session-input-v1.json). Use `remoteble session --jsonl` for coding
agents; use `remoteble shell` for a human REPL. The shell supports foreground/background streams and
process-level pipes, but is not an OS shell and does not execute child processes. Human-readable data
goes to stdout; diagnostics go to stderr.

## Documents

| Document | What it covers |
|---|---|
| [`docs/state-model.md`](docs/state-model.md) | **Read first.** How connections and leases survive between CLI invocations |
| [`docs/cli-reference.md`](docs/cli-reference.md) | Command surface, output contract, exit codes, configuration |
| [`docs/safety-model.md`](docs/safety-model.md) | Write policy, where enforcement actually lives, untrusted device data, audit |
| [`CONTRIBUTING.md`](CONTRIBUTING.md) / [`SECURITY.md`](SECURITY.md) | Contribution checks and vulnerability reporting policy |
| [`docs/slim-client.md`](docs/slim-client.md) | Embedded protocol/WebSocket/CBOR client boundary and update rules |
| [`docs/session-protocol.md`](docs/session-protocol.md) | Persistent machine session and human shell protocol |
| [`docs/logging-audit.md`](docs/logging-audit.md) | Redacted audit/debug logging, retention, and failure policy |

## Repository layout

```text
remoteble-tools/
├── core/        high-level, interface-independent operations
├── cli/         commands, formatting, configuration
├── skills/      portable Agent Skills
├── schemas/     versioned output and recipe schemas
├── examples/    configuration examples
├── docs/
└── integration-tests/
```

`core` implements the shared operation layer for the CLI, including BLE lifecycle behavior.
