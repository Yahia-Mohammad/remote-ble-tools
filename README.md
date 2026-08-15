# remoteble-tools

**v0.1 pre-release.** A command-line client and companion Agent Skill for
[RemoteBLE](https://github.com/Yahia-Mohammad/remote-ble). The remaining release gates are hosted
CI evidence and the documented desktop hardware matrix.

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
core product. **The CLI is independently useful, testable, and scriptable** — which is the main
reason to build this before, or instead of, an MCP server.

## Build and run

Build a self-contained native executable on Apple Silicon:

```sh
./gradlew :cli:linkRemotebleReleaseExecutableMacosArm64
cli/build/bin/macosArm64/remotebleReleaseExecutable/remoteble.kexe --help
```

The same Kotlin Multiplatform source also links Linux x64 and ARM64 executables with
`:cli:linkRemotebleReleaseExecutableLinuxX64` and
`:cli:linkRemotebleReleaseExecutableLinuxArm64`. `:cli:fatJar` remains available as a JVM
compatibility artifact, but the native executable is the primary distribution and requires no
installed JRE. Release CI should still smoke-test each binary on its matching operating system.

Release archives include a manual page in `dist/man/remoteble.1` and Bash, Fish, and Zsh completion
assets in `dist/completions/`.

Every archive also includes the human-readable Agent Skill at `dist/skills/remoteble`, and releases
publish a separate `remoteble-skill-<version>.zip` with a SHA-256 sidecar. Install the embedded copy
without MCP or credentials with `remoteble skills install`; see
[`docs/agent-skill.md`](docs/agent-skill.md#installation-without-mcp) for targets, updates, and
uninstallation.

The distribution also includes `rble`, a short launcher alias. Use [examples/config.yaml](examples/config.yaml)
as a starting configuration and set the agent token in its configured environment variable.
Configuration precedence is command-line options, selected profile, configuration file, then built-in defaults.

Structured output uses the versioned [result envelope schema](schemas/result-envelope-v1.json) and the
[persistent session schemas](schemas/session-input-v1.json). Use `remoteble session --jsonl` for coding
agents; use `remoteble shell` for a human REPL. The shell supports foreground/background streams and
process-level pipes, but is not an OS shell and does not execute child processes. Human-readable data
goes to stdout; diagnostics go to stderr. The CLI never falls back to legacy `/api/state` or treats
session-local slots as global capacity.

## Documents

| Document | What it covers |
|---|---|
| [`docs/concept.md`](docs/concept.md) | Problem, users, competitive landscape, differentiators, non-goals, what would falsify this |
| [`docs/state-model.md`](docs/state-model.md) | **Read first.** How connections survive between CLI invocations, and the upstream change it requires |
| [`docs/cli-reference.md`](docs/cli-reference.md) | Command surface, output contract, exit codes, configuration |
| [`docs/safety-model.md`](docs/safety-model.md) | Write policy, where enforcement actually lives, untrusted device data, audit |
| [`docs/agent-skill.md`](docs/agent-skill.md) | Skill layout, agent workflows, the BLE + serial correlation loop |
| [`docs/skill-validation.md`](docs/skill-validation.md) | Deterministic checks and the manual multi-agent release evidence record |
| [`docs/mvp-scope.md`](docs/mvp-scope.md) | v0.1 scope, deferrals with reasons, acceptance scenarios |
| [`docs/profiles.md`](docs/profiles.md) | Semantic device profiles and their loop with RemoteBLE simulation (deferred, v1.1+) |
| [`docs/implementation-plan.md`](docs/implementation-plan.md) | Phased implementation, upstream gates, architecture, tests, and release criteria |
| [`docs/progress.md`](docs/progress.md) | Current implementation status, verification evidence, and external release gates |
| [`docs/hardware-validation.md`](docs/hardware-validation.md) | Manual v0.1 desktop-agent matrix, clean-install smoke, and redacted release evidence record |
| [`CONTRIBUTING.md`](CONTRIBUTING.md) / [`SECURITY.md`](SECURITY.md) / [`CHANGELOG.md`](CHANGELOG.md) | Contribution checks, private vulnerability reporting policy, and pending release notes |
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
├── examples/    shell scripts and recipes
├── docs/
└── mcp/         reserved for a future MCP facade
```

`core` implements the operation layer used by both the CLI and any future MCP server. Neither
interface independently implements BLE lifecycle behaviour. See
[`docs/concept.md`](docs/concept.md#on-mcp-later-if-at-all) for why MCP is reserved rather than
planned.

## External release gates

RemoteBLE **0.11.0** is released and carries every contract this CLI depends on: lease-grace and
global-slot accounting, `agent.status`, holder diagnostics, `identifierFormat`, and agent-enforced
write policy. The 0.1 release is no longer gated on upstream work — it is gated on validation
evidence and release packaging. See [`docs/release-plan.md`](docs/release-plan.md).

## Taglines under consideration

> Remote BLE from your terminal — and from your coding agent.

> Commands and pipes for BLE hardware anywhere.
