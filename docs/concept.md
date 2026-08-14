# Concept

## Problem

BLE development usually requires platform-specific applications and graphical tools running on the
machine that owns the Bluetooth adapter. That creates friction when:

- Development happens in a VM, container, simulator, or remote environment.
- The device sits in a shared lab.
- CI needs to run integration tests against real hardware from radio-less runners.
- A coding agent needs to inspect or test a BLE device.
- Engineers need repeatable command-line evidence for a bug report.
- Several developers need coordinated access to scarce hardware.
- Platform Bluetooth behaviour differs between development machines.

Existing BLE command-line tools operate the local adapter. RemoteBLE already solves the harder
infrastructure problem — locating the radio beside the device and exposing a resilient,
authenticated, lease-aware GATT session over the network. The missing piece is a general
command-line interface.

## Target users

- **Embedded and BLE engineers** — explore services, inspect values, test commands, capture
  notification streams.
- **Application developers** — exercise real peripherals without writing a throwaway app.
- **QA engineers** — build repeatable hardware-in-the-loop checks.
- **CI systems** — drive physical devices on a lab machine from runners with no radio.
- **Support engineers** — inspect a remote device and collect structured diagnostics.
- **AI coding agents** — inspect hardware while implementing or debugging BLE code.

The ordering is deliberate. Agents are the sixth audience, not the first.

## Goals

- A stable CLI over the practical RemoteBLE operation set.
- Works against remote **and simulated** agents.
- Useful to humans with no AI agent involved.
- Predictable, machine-readable, versioned output.
- Composes with pipes, `jq`, files, and CI.
- Bounded event streams for scans and notifications.
- Safe defaults for operations that affect physical devices.
- Connection state preserved across the steps of a procedure.
- A portable Agent Skill for coding-agent workflows.
- A reusable `core` that a future MCP facade could sit on.

## Non-goals

The initial project will not:

- Implement another platform-native BLE stack.
- Access a local adapter without a RemoteBLE agent.
- Decode every proprietary device protocol.
- Replace packet sniffers or HCI analysers.
- Provide a graphical BLE explorer.
- Introduce a general-purpose programming language.
- Manage a distributed farm of agents.
- Permit unrestricted autonomous writes by default.

A developer wanting local hardware still runs a RemoteBLE agent on the same machine and points the
CLI at `localhost`. On macOS that means using `agent/run-agent.sh` rather than a bare JVM — TCC
only grants Bluetooth to a signed `.app` bundle.

## Competitive landscape

Surveyed 2026-08-04. Every general BLE MCP server and most BLE CLIs bind to the **local** adapter.

| Project | Stack | Coverage | Traction | What it leaves open |
|---|---|---|---|---|
| [es617/ble-mcp-server](https://github.com/es617/ble-mcp-server) | Python / Bleak | Full local GATT, protocol-spec files, generated device plugins, tracing, stdio + SSE + HTTP, write allowlists | ~15★, active | MCP endpoint and radio stack in one process — no separate radio hosts, no leasing, no reconnect reconciliation, no phone agents, no simulation |
| [stass/blew](https://github.com/stass/blew) | Swift / CoreBluetooth | CLI **and** MCP, 18 tools, SIG decoding, peripheral emulation and GATT cloning | ~13★ | macOS-only, local Mac radio. Validates the CLI-first-then-MCP ordering |
| [ble-mcp-test](https://github.com/ble-mcp-test/ble-mcp-test) | Node / Noble.js | WebSocket BLE bridge, Web Bluetooth mock, session ids, "share BLE hardware across your team" | **0★, 351 commits** | Closest architecture. Browser-test shaped; device farm and richer tooling are roadmap; no simulation |
| [mcbluetooth](https://pypi.org/project/mcbluetooth/) | Python / BlueZ | BLE/GATT, Classic, pairing, audio, HFP, OBEX, packet monitoring | small | Linux-specific, local stack |
| [Hypijump31/bluetooth-mcp-server](https://github.com/Hypijump31/bluetooth-mcp-server) | Python | Scan-oriented, BLE + Classic | small | Scanning only, local |
| [MCP-Edge](https://pypi.org/project/mcp-edge/) | Python | Device-native MCP tools over BLE/serial/Wi-Fi | experimental | BLE as transport to an MCP-aware device, not GATT control |
| [blueSPY MCP](https://pypi.org/project/bluespy-mcp/) | Python | Drives a sniffer, analyses captures | small | Complementary analysis, not a central |

## Read the demand signal honestly

"Several implementations, none dominant" reads as an open field. It is at least as consistent with
**low demand**.

The strongest evidence is `ble-mcp-test`: someone independently built a WebSocket BLE bridge whose
README pitches sharing BLE hardware across a team and names Claude Code explicitly — the same
architecture, the same positioning — put 351 commits into it on an easy-install Node stack, and
attracted zero stars.

Two conclusions, pointing in opposite directions:

1. **Against:** the agent-facing pitch has been tried and did not find users. Do not build this
   expecting a product.
2. **For:** packaging friction is evidently not the binding constraint, which removes the argument
   for implementing in Python/TS to obtain `uvx`/`npx` distribution. The cheap path — Kotlin/JVM
   reusing the client SDK — is the right one, and effort saved on packaging goes into the tool.

The audience owns nRF Connect, LightBlue, and a serial console already.

**This is why the CLI is the right first artifact and the MCP server is not.** An MCP server has
exactly one consumer, and the evidence for that consumer is weak. A CLI is used by humans, scripts,
and CI on day one; agent usage is upside rather than the whole bet.

## Why a CLI suits agents better than a tool API anyway

Beyond derisking, the shell shape has concrete advantages for coding agents:

- **Context economy.** `remoteble scan --jsonl | jq 'select(.rssi > -60)'` filters before anything
  reaches the model's context. A tool call returns the whole result set and the model filters by
  reading it. BLE scans are high-volume and repetitive, so this compounds over a session.
- **Streaming is native.** `remoteble observe … --jsonl` is a long-running process writing to
  stdout. Tool-call interfaces need a buffered "collect notifications" workaround, because many MCP
  clients do not reliably surface unsolicited server-initiated messages —
  [es617 documents this directly](https://github.com/es617/ble-mcp-server#known-limitations).
- **Composition with the rest of the toolchain.** Correlating BLE against a serial console is two
  timestamped streams and `sort -m`. Across two tool servers it is awkward. See
  [`agent-skill.md`](agent-skill.md#the-ble--serial-correlation-loop).
- **On-demand loading.** A Skill loads when relevant; tool schemas occupy context permanently.

The cost is permission granularity — see [`safety-model.md`](safety-model.md#why-this-matters-more-for-a-cli-than-for-a-tool-api).

## Differentiators, in priority order

1. **Remote-first.** The command runs where the agent runs; the radio is elsewhere.
2. **Lab-grade ownership and resilience** — exclusive leases, reconnect reconciliation.
3. **Simulation and reproducible evaluation** — a declared GATT world, no hardware.
4. **Phone-as-radio.**
5. **One BLE substrate** shared by CLI users, Kable apps, CI, and agents.
6. Later: multi-agent routing and device farms.

## On MCP: later, if at all

The repository reserves `mcp/`, and `core` is deliberately interface-independent so a facade can be
added without reimplementing lifecycle behaviour. But MCP is not planned work.

Reassess only when the CLI has demonstrated usage, and then only for hosts that cannot run a shell
command. By that point the facade is a thin mapping over `core`, and — more importantly — there
will be evidence about whether anyone wants it. `blew` took exactly this path: CLI first, MCP
after.

One consideration if it is ever built: a non-Kotlin implementation would be the first genuine test
of RemoteBLE's claim to a transport- and language-agnostic wire protocol, since today both
endpoints are Kotlin by the same author. The protocol module is ~770 lines. That is a
protocol-validation exercise, not a distribution one.

## What would falsify this

Decide these before writing code, and write the answers down:

- **Does anyone want it?** Find three people who would use the CLI. If the honest answer is "it is
  a demonstrator for the SDK," scope it as one and do not build the recipe engine or the farm.
- **Does a competitor add a remote transport?** A plausible small change for a more mature project.
  The moat is leasing plus simulation plus phone agents, not the network hop alone.
- **Can an agent actually drive BLE bring-up?** If a competent model cannot reach a useful
  conclusion about an unknown peripheral with these commands, the Skill half is premature. Test
  this against the simulator early, before polishing the surface.
- **Does the state model hold?** If the grace-window change lands and contention on shared rigs
  becomes the top complaint, the daemon in [`state-model.md`](state-model.md#rejected-alternative-a-local-daemon)
  was the right call after all.

## Repository boundary

A sibling repository consuming `dev.warsha.remoteble:client-sdk` from Maven Central like any
third-party consumer. Independent release cadence, independent security surface, and it makes the
CLI the first real external consumer of the published SDK — a useful forcing function on that API.

## Sequencing

1. RemoteBLE 0.11.0 tagged and published to Maven Central.
2. Agent grace window made configurable ([`state-model.md`](state-model.md)).
3. CLI v0.1 ([`mvp-scope.md`](mvp-scope.md)).
4. Agent Skill ([`agent-skill.md`](agent-skill.md)).
5. Reassess everything else.
