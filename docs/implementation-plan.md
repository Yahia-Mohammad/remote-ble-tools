# Implementation plan

> **Historical design record.** This document preserves the original v0.10-era implementation
> sequence; its dated delivery tables and proposed hardware script are not the current status or
> execution plan. RemoteBLE v0.11.0 superseded the upstream assumptions. Use
> [`progress.md`](progress.md) for current evidence, [`release-plan.md`](release-plan.md) for the
> remaining work, and [`hardware-validation.md`](hardware-validation.md) for the deliberately manual
> physical validation protocol.

This plan turns the v0.1 design into a sequence of reviewable changes. It is based on the
RemoteBLE `v0.10.0` source and tag inspected on 2026-08-04. The CLI remains a separate repository
and embeds only the wire-protocol, CBOR, WebSocket, session, remote scan, and remote GATT source it
needs. It does not consume the app-facing SDK or any Kable API.

Load-bearing upstream claims below cite the file that establishes them, so a reader can check the
plan against the tag rather than against this document's memory of it.

## Outcome

v0.1 is complete when a released `remoteble` distribution can drive the twelve simulator-backed
acceptance scenarios in [mvp-scope.md](mvp-scope.md), preserve a lease across separate process
invocations, produce schema-validated output, and run the same smoke procedure across the desktop
macOS/Linux × JVM/Rust matrix. Mobile and Raspberry Pi validation remain outside this gate.

The machine-facing interface is `session --jsonl`; `shell` shares the operation engine but is not an
OS shell and has no internal pipelines. Process-level pipes remain supported. Audit JSONL is always
enabled, debug files are opt-in, and credentials and payload contents are never logged.

The implementation order is deliberately vertical: establish the session and state model first,
then ship one complete read-only workflow, then add writes and longer-lived streams. Formatting,
policy, and error behavior are part of each slice rather than a final polish phase.

## Delivery status (2026-08-09)

The architecture decision is implemented: the command tree uses Clikt, and `core` embeds the
minimal protocol/CBOR/WebSocket/session/remote-GATT client source needed by the CLI. It has no
`client-sdk`, Kable, Koin, or local-radio dependency. Native macOS ARM64, Linux x64, and Linux
ARM64 release executables link successfully; the macOS binary has been smoke-tested without a JVM.

The current worktrees also implement the additive management and write-policy protocol slice:
status, global slots, structured busy-holder disclosure, operator-scoped WebSocket sessions, and
agent-side strict write policy. The CLI has matching status/slots/write adapters, explicit write
sources, local exact-rule checks, and bounded observe behavior. This is **not** yet a release claim:
the work has not been committed or run against packaged agents/hardware. See
[progress.md](progress.md) for the current evidence and gates.

### Upstream reconciliation decision (2026-08-09)

The rebased `remote-ble-public` `feat/cli-readiness` branch is the implementation base. Preserve
its mature global-slot, warm-resume, status, disclosure, wildcard policy, raw-client, and parity
work; port only the remaining revised CLI contract deltas. Keep `agent.status`, `slots`, and
`write.policy` unversioned; add only `lease.holder` for structured busy-holder disclosure. Slots
are consumed from the immediate global event, not a new query. The branch remains unmerged until
hardware evidence is complete.

| Phase | Delivery state | Remaining work |
|---|---|---|
| 1 — Build and executable skeleton | Complete foundation | Matching-host Linux smoke tests and archive execution validation |
| 2 — Models, output, error boundary | Partially complete | Broader schema/golden/error exhaustiveness coverage |
| 3 — Configuration and advisory policy | Partially complete | Full policy ledger, permission, and boundary coverage |
| 4 — Session and introspection | Implemented locally | End-to-end session/lease proof and scoped privacy tests against packaged agents |
| 5–6 — Scan and read-only GATT | Implemented | Simulator/reference-agent and hardware acceptance |
| 7 — Writes | Implemented locally | Cross-process ledger/rate audit, raw-client enforcement proof, and packaged-agent coverage |
| 8 — Observe | Implemented foundation | Reconnection and termination behavior against an agent |
| 9–10 — Acceptance and distribution | Pending | Fixtures, CI matrix, packages, release materials, and frozen Skill |

## Decisions to lock before implementation

These choices close gaps in the current design documents and keep later pull requests from
inventing incompatible behavior.

1. **Runtime and build:** Kotlin Multiplatform, Gradle wrapper, Kotlin DSL, and a version catalog.
   Release native macOS ARM64 and Linux x64/ARM64 executables; retain a JVM fat JAR only for
   compatibility and black-box testing. The long name is `remoteble`; `rble` is an alias.
2. **CLI parser:** use Clikt for nested commands, validation, generated usage text, and shell
   completions. Keep Clikt and process-exit behavior in `cli`; `core` has no CLI dependency.
3. **Client boundary:** embed the v1 protocol models/CBOR codec plus the BLE-agnostic WebSocket,
   session, remote scan, and remote GATT request layers. Exclude Kable, Koin, local-radio backends,
   identifier translation, and app-facing factories.
4. **Client surface:** implement GATT operations with `AgentSession`, `RemoteGattClient`, and
   `RemoteScanSource`. Protocol handles stay opaque strings and are never reconstructed as Kable
   objects.
5. **Process lifecycle:** every invocation creates exactly one session and waits for handshake
   readiness. Lease-acquiring commands issue idempotent `connect`, perform the requested operation,
   and close only the WebSocket session. Only `disconnect` sends `Op.Disconnect`. Do not call
   `RemotePeripheral.shutdown()` or any cleanup path that releases the lease implicitly.
6. **Reconnect policy:** short one-shot commands use a bounded connection attempt and fail rather
   than leaving a background reconnect loop alive. `observe` may reconnect within its overall
   deadline and relies on embedded-client reconciliation. The command's total deadline always dominates
   per-attempt timeouts.
7. **Configuration:** YAML is the human configuration format; use KAML over Kotlin serialization
   for strict config decoding, and Kotlin serialization JSON for structured output. Resolve values
   in this order:
   command option -> environment -> selected YAML profile -> built-in default.
8. **Secret input:** support a token environment-variable name, an explicit `--token-stdin` mode,
   and a future credential-provider interface. Refuse `--token-stdin` when write payload bytes also
   come from stdin. Do not add `--token`, literal token YAML fields, or token-bearing URLs. `config
   show`, exceptions, debug logs, and test snapshots must redact secrets.
9. **Stable client identity:** default to `rble-` plus a stable, privacy-preserving hash of the OS
   user and machine identity. Allow an explicit `agent.clientId`, but validate its byte length and
   warn when a templated or machine-derived ID appears to have been copied. The resolved ID is
   reused for every process on that machine and endpoint.
10. **UUIDs:** accept 16-, 32-, and 128-bit BLE UUIDs case-insensitively, normalize them to the
    Bluetooth base UUID at the core boundary, and retain the compact spelling for human display
    where unambiguous. Bundle a checked-in, provenance-documented Bluetooth SIG assigned-number
    table; never fetch names at runtime.
11. **Read formats:** v0.1 supports bytes, hex, Base64, UTF-8, `uint8`, `int8`, and signed/unsigned
    16- and 32-bit little-/big-endian values. A decoded value is optional and never replaces
    `hex`, `base64`, and `length` in structured output. Exact byte-length and UTF-8 errors are usage
    or decoding failures, not silent coercions.
12. **Scan semantics:** a scan always has a duration (default 5 seconds) and a hard event ceiling
    (default 1,000). Wire-supported service/name filters are sent to the agent; minimum RSSI and
    similar filters are applied locally. JSON and human modes aggregate by handle and include
    first/last seen plus event count; JSONL emits every accepted event with sequence and timestamp.
13. **Observe semantics:** require `--count`, `--timeout`, or `--unbounded`. `--unbounded` is
    refused unless `policy.allowUnboundedStreams` is true. Default policy still caps event size,
    buffer depth, and count; Ctrl-C must stop the subscription and close the transport cleanly.
14. **Write input:** require exactly one of `--hex`, `--base64`, `--text`, or stdin with an explicit
    `--input` codec. Require an explicit write type; defaulting to with-response is allowed only
    after the help and output identify it clearly. Never infer bytes from a display string.
15. **Exit code 8:** use it only when a non-idempotent operation may have reached the agent but no
    definitive reply arrived. A timed-out read or scan is not indeterminate; it maps according to
    the ordinary timeout/transport failure table.
16. **Untrusted text:** all device strings are length-bounded and control-character escaped before
    rendering. Human output labels them as data (for example, `name=<...>`); JSON serializers handle
    escaping. No core operation consumes a name or decoded string produced by a prior operation.
17. **Device handles are opaque and only as portable as the agent lease makes them.** The slim
    client does not request identifier translation (see U6). Lease-acquiring commands accept a
    selector (`--name`, `--service`) as well as a handle and resolve
    it in-process; every command that takes a lease echoes the handle to reuse; and an
    `UNKNOWN_DEVICE` reply for a syntactically valid handle is reported as a stale-handle diagnostic
    naming the selector remedy, not as a bare "device not found".
18. **Retry and timeout policy is set where the embedded client exposes it — the session.**
    `RemoteGattClient` and `RemoteScanSource` take no retry parameter; they forward to
    `AgentSession.request(op, timeout)` and leave `retry` null, so the session's own policy applies.
    The only two levers are therefore `DefaultAgentSession(retryPolicyFor = …)`, which receives the
    `Op` and so is already per-operation-kind, and a direct `session.request(op, retry = …)` for a
    single call. The gateway consequently holds the `AgentSession`, not just a `RemoteGattClient`,
    and supplies its own `retryPolicyFor`: `RetryPolicies.None` for every non-idempotent op, a
    bounded policy elsewhere. This matters because `timeout` is applied **per attempt**, so an
    inherited default silently turns one user-visible deadline into several; the command's own
    deadline wraps the whole call. Set `RemoteTimeouts` from configuration too — its defaults
    (connect 30 s, discover 20 s) are tuned for a relayed worst case, not for a CLI deadline.
19. **Scan concurrency is a negotiated mode, not an assumption.** 0.10.0 agents advertise exactly
    one of `scan.concurrency.multiplexed`, `single`, or `uncontrolled`, and the embedded session offers
    all three, so the negotiated intersection names the agent's mode. `agent capabilities` reports
    it, and `scan` maps `SCAN_UNAVAILABLE` (a `single`-mode agent whose scan is held elsewhere)
    distinctly from `AGENT_BUSY`, so a caller can tell "wait and retry" from "misconfigured".

Record the final forms of these decisions in `cli-reference.md` and version the configuration and
output schemas before implementing commands.

### Documentation corrections required first

Two sibling documents state upstream facts that `v0.10.0` has overtaken. Correct them in the same
pull request that lands these decisions, or the plan will be read against them:

- [state-model.md](state-model.md) says the grace windows are wired to "no environment variable or
  command-line flag". The JVM agent reads `REMOTE_BLE_LEASE_GRACE_MS` and
  `REMOTE_BLE_TRANSPORT_GRACE_MS` (`agent/src/jvmMain/.../Main.kt`), and `agent-rs` takes
  `--lease-grace-ms` / `--transport-grace-ms` (`agent-rs/src/main.rs`). What remains true is the
  10-second *default* and the absence of any phone-agent setting — see U2.
- [README.md](../README.md) describes 0.10.0 as a release candidate with publication outstanding.
  It is tagged and released; the open item is Maven Central resolution from a clean cache (U1).

## Target repository structure

```text
remoteble-tools/
├── build.gradle.kts
├── settings.gradle.kts
├── gradle/
│   └── libs.versions.toml
├── core/
│   └── src/
│       ├── commonMain/kotlin/dev/warsha/remoteble/
│       │   ├── protocol/       embedded wire models and CBOR codec
│       │   ├── client/         WebSocket, session, scan, and remote GATT slice
│       │   └── tools/core/     CLI operation, config, output, and gateway layer
│       ├── commonTest/
│       ├── jvmMain/
│       └── nativeMain/
├── cli/
│   └── src/
│       ├── commonMain/kotlin/dev/warsha/remoteble/tools/cli/
│       │   └── Main.kt         Clikt command tree and composition root
│       ├── jvmTest/
│       └── build/              JVM compatibility JAR and target-native executables
├── schemas/
│   ├── config/v1/
│   └── output/v1/
├── integration-tests/          black-box CLI + released simulated agent
├── examples/                   executable shell procedures
├── skills/remoteble/           added after the CLI surface stabilizes
└── docs/
```

`RemoteBleGateway` is the CLI-facing port in `core`; its production implementation uses the
embedded protocol client. Tests exercise CBOR and client defaults in common code and command
behavior on the JVM. A future facade should call this operation layer rather than duplicating
session lifecycle behavior.

## Upstream readiness work

Some advertised commands cannot meet their documented contract with RemoteBLE 0.10.0. Resolve
these before declaring the corresponding CLI slice complete.

### U1. Published protocol compatibility and release fixture

- Confirm the embedded source remains wire-compatible with the selected RemoteBLE release.
- Pin the released simulated-agent fat JAR or OCI image by version and SHA-256 for black-box tests.
- Add a CI job that fails if release builds depend on a sibling checkout or `mavenLocal()`.

**Gate:** a clean checkout builds the CLI and launches the test agent without any sibling
repository.

### U2. Grace-window behavior on every reference agent

Configurability is **already shipped on the desktop agents** and is not blocking: the JVM agent
reads `REMOTE_BLE_TRANSPORT_GRACE_MS` and `REMOTE_BLE_LEASE_GRACE_MS`, and `agent-rs` takes
`--transport-grace-ms` / `--lease-grace-ms`. Simulator acceptance can therefore set the window it
needs from day one, which moves this item off the critical path for CI. What remains:

- the rebased desktop branch now uses a 120-second transport-grace default and retains a 10-second
  BLE-disconnect grace; remaining work is hardware evidence;
- mobile and Raspberry Pi agents are explicitly outside the v0.1 gate.

Also add an upstream regression proving that a same-principal, same-client-id reconnect resumes the
warm lease without an extra physical reconnect. Calling backend `connect()` against an already warm
link must be demonstrably idempotent and must not create duplicate disconnect watchers.

**Gate:** acceptance scenario 10 passes with a 30-second pause on the simulator and desktop agents,
and agent instrumentation shows one physical connection.

### U3. A status contract that works remotely, on every reference agent

The existing operator-only `/api/state` endpoint cannot carry this command. It is an internal
dashboard feed reporting `inGrace` but not remaining grace; its schema is not a compatibility
surface; it is refused for non-loopback callers unless `REMOTE_BLE_ALLOW_INSECURE_LAN` is set,
because it is plain HTTP carrying the operator token; and `agent-rs` has no HTTP server at all, so
the Rust agent could not serve it in any form. A remote-first CLI whose status command only works
against localhost, on one of three agent implementations, is not worth building.

So define the contract on the transport the CLI already has authenticated and, where deployed
behind a proxy, already has encrypted:

- **Preferred: an additive protocol operation**, capability-gated (for example `agent.status`),
  answered over the existing WebSocket session. It reaches every reference agent — JVM, Rust,
  Android, iOS — over the same authenticated, TLS-terminable path as every other op, and needs no
  second credential distribution story. Disclosure is scoped to the caller: a normal principal sees
  its own leases and aggregate counts; a caller presenting operator scope sees holders.
The existing HTTP dashboard remains separate and is not a CLI fallback. A standalone operator bearer
session may read management status/slots but may not perform BLE operations.

Either shape carries: agent identity/version, uptime, effective grace settings, connected client
summaries, and per-lease handle, display name, holder identity appropriate to the caller,
connected/in-grace state, and `releaseAt` or `remainingGraceMs`.

Accept `REMOTE_BLE_OPERATOR_TOKEN` (or the configured environment-variable name) as the separate
operator bearer. A normal BLE bearer token must not silently gain operator access.

**Gate:** `agent status` returns the documented DTO against a *remote* JVM agent and a *remote*
`agent-rs` agent, and produces a specific error — not a connection failure — for missing operator
credentials, a loopback-only refusal, and an agent too old to advertise the capability.

### U4. Slot accounting that means something to a new process

Two defects, and the second is the one that would ship a misleading number.

First, the `slots` capability emits `SlotState` immediately after a client negotiates the capability;
there is deliberately no explicit slot query operation.

Second, the count is **per client session**: `free` is computed as `maxConnections - connected.size`
against the `connected` set of the caller's own `BleAgent` instance
(`agent/src/commonMain/.../BleAgent.kt`). A newly connected client therefore reads full capacity
regardless of what other sessions are using, and warm leases held by a client that is inside its
transport grace — precisely the state this CLI creates on every invocation — are invisible. For a
process-per-command tool the reported number would be wrong in the exact situation the command
exists to explain: `agent slots` run right after `remoteble inspect` would report the peripheral
the caller itself is holding as free capacity.

The accepted implementation makes accounting agent-global and lease-aware (counting live
connections plus leases in grace), which is what the registry already knows.

**Gate:** a newly connected client receives one snapshot without connecting a peripheral, and a
second client's snapshot reflects a peripheral the first client holds — including while that first
client is disconnected but inside its transport grace.

### U5. Diagnosable lease denial

`PERIPHERAL_BUSY` returns a safe human message plus optional structured holder data gated by the new
`lease.holder` capability. The principal is disclosed according to scope; the full client ID is
revealed only to the same principal or operator. Older clients continue to use the safe message.

**Gate:** the contention test can assert who holds the lease without parsing agent logs.

### U6. Handle identity for a non-Kable, multi-process client

The slim client deliberately does not offer `identifier.translate` and does not declare a
Kable-specific identifier format in `ClientHello`. It receives and returns the agent's opaque wire
handles, so no client-side identifier reconstruction or translation map exists. This is both the
reason the CLI can be Kable-free and the expected behavior that must be verified against each agent.

Selectors remain the robust first-connection path: resolve a selector within the same session, then
echo the resulting opaque handle. The agent's lease behavior determines whether that handle is
usable by a later process. A syntactically valid handle that produces `UNKNOWN_DEVICE` must be
reported with the selector remedy, never as an unexplained device-not-found failure.

**Gate:** a black-box test runs scan → connect → inspect/read → disconnect as separate packaged
processes against each reference agent, and a selector-based flow remains usable if a returned
handle is not session-portable.

### U7. Agent-side write policy

Per-principal write allowlists are implemented in the rebased desktop agents and remain the actual
security boundary. Preserve explicit wildcard matching and the optional exact/wildcard device rule;
test the same allowed/denied characteristic matrix through the CLI and a second raw protocol client
so enforcement is proven independently of this executable.

## Implementation phases

### Phase 1 — Build and executable skeleton — complete foundation

- Add the Gradle wrapper, version catalog, KMP `core` and `cli` projects, formatting, test tasks,
  and dependency locking.
- Add a Clikt root command with the complete documented command tree. Unimplemented leaves return
  a clear unavailable error; released artifacts must contain no placeholder commands.
- Implement `version`, build metadata injection, `--help`, `--version`, global output switches,
  `--config`, `--profile`, endpoint/client-id overrides, verbosity, and color policy.
- Keep the embedded client silent by default and assert JSON stdout remains valid.
- Build a JVM compatibility fat JAR and native executables. Add launch smoke tests on macOS and
  matching Linux hosts.

**Status:** Clikt parsing, help/version, completion generation, JVM fat-JAR build, and native links
are complete. The macOS native smoke test passes. Native archives, distribution scripts, and
matching-host Linux smoke tests remain.

### Phase 2 — Versioned models, output, and error boundary

- Define serializable result envelopes for agent information, scan summaries/events, GATT trees,
  reads, writes, notifications, RSSI, descriptors, and configuration validation.
- Every envelope has `schemaVersion = 1`, `type`, and operation-specific data. Stream records also
  carry UTC timestamp and monotonically increasing sequence.
- Write JSON Schemas under `schemas/output/v1`; validate every JSON/JSONL golden fixture against
  them in tests. Treat field removal/type changes as breaking.
- Implement renderers behind one `OutputSink`: human, JSON, JSONL, hex, Base64, raw, and quiet.
  Reject incompatible modes before connecting.
- Centralize `DomainError -> stderr diagnostic + exit code`. Preserve the agent error kind in
  structured failures without leaking credentials or raw exception internals.
- Add golden tests for stdout/stderr separation, terminal/non-terminal color, hostile strings,
  control characters, and broken-pipe behavior.

Error mapping is a function of three inputs, not of `ErrorKind` alone: the kind, whether the
operation was idempotent, and whether the failure happened before or after dispatch. A timed-out
read and a timed-out write are the same kind and must not produce the same code. Classify in this
order, first match wins:

1. the CLI never reached the agent — argument, codec, config, or policy failure → 2 or 3;
2. the failure is a specific, actionable condition with its own code below → that code;
3. the operation was **non-idempotent and had been dispatched** → 8, regardless of kind;
4. the kind is `transient` → 9, because a later identical attempt may succeed;
5. otherwise → 1, a definitive failure that a retry cannot change.

| Condition | Exit |
|---|---:|
| Argument/output/input codec error, `INVALID_REQUEST` | 2 |
| Invalid config or advisory policy refusal | 3 |
| Handshake, auth, or connect failure before any op; `INCOMPATIBLE_PROTOCOL` | 4 |
| `UNKNOWN_DEVICE`, unresolvable selector, or ambiguous selection | 5 |
| `PERIPHERAL_BUSY` | 6 |
| Missing negotiated capability, `UNSUPPORTED` | 7 |
| Any dispatched non-idempotent op ending in `TIMEOUT` or `TRANSPORT_LOST` | 8 |
| Idempotent op, transient link kind: `TIMEOUT`, `TRANSPORT_LOST`, `CONNECTION_FAILED`, `DISCONNECTED`, `NOT_CONNECTED`, `READ_FAILED` | 9 |
| Agent or host resource kind: `NO_CONNECTION_SLOT`, `AGENT_BUSY`, `SCAN_UNAVAILABLE`, `RADIO_OFF` | 9 |
| Non-transient kinds: `GATT_ERROR`, `CHARACTERISTIC_NOT_FOUND` | 1 |

Exit 9 is an addition to the table in [cli-reference.md](cli-reference.md#exit-codes) and must be
recorded there. It means "this may work if you try again" — the one thing exit 1 must never be
confused with, and the distinction a scripted or agentic caller acts on. Note that `WRITE_FAILED`
is transient as a *kind* but reaches rule 3 first on a dispatched write, which is the intended
outcome: safety wins over the kind's own annotation. Structured failures also carry the agent's
`kind` and `transient` flag, so a machine-readable consumer never infers retryability from the exit
code alone.

**Done when:** all output types have schema and human fixtures, and every known `ErrorKind` has an
explicit, tested mapping — including an exhaustiveness test that fails when the protocol adds a kind.

### Phase 3 — Configuration and advisory policy

- Define `schemas/config/v1` and a strict YAML decoder that rejects unknown fields and invalid
  duration/UUID/endpoint values.
- Support a default config location, `REMOTE_BLE_CONFIG`, named profiles, environment overrides,
  and command overrides. Document the exact environment-variable names.
- Implement `config show` as a resolved, source-annotated, secret-redacted view.
- Implement `config validate` for reachability-independent checks plus optional `--connect` checks:
  config permissions, token source availability, client-id stability, endpoint scheme/TLS warnings,
  policy contradictions, duplicate rules, and unsafe broad rules.
- Model write rules by canonical endpoint, exact device handle, service, characteristic, maximum
  bytes, allowed write type, and rolling-window rate. `readOnly: false` with no rules denies all.
- Enforce rate across separate invocations using a bounded, file-locked ledger in the user state
  directory, written atomically and keyed by endpoint/client/device/characteristic. Treat it as an
  advisory guardrail and garbage-collect expired entries.
- Add stream/result/payload hard bounds. Command options may lower configured limits but cannot
  raise policy maxima.

**Done when:** precedence, redaction, copied-client-id warnings, file permissions, rule matching,
cross-process rate limiting, and concurrent ledger updates are covered by tests.

### Phase 4 — Session adapter and agent introspection

- Build a session factory around `WebSocketAgentTransport` and `DefaultAgentSession` with the
  resolved endpoint, token provider, stable client ID, bounded reconnect policy, all known client
  capabilities offered, and — per decision 18 — an explicit `retryPolicyFor` and configured
  `RemoteTimeouts`. The gateway keeps the `AgentSession` itself so a single call can override the
  policy; `RemoteGattClient` alone cannot express that.
- Wait for `SessionReadiness.READY` or a terminal state before dispatching an operation. Surface
  incompatible protocol separately from generic network failure.
- Expose negotiated capabilities through the gateway; unknown capability strings are preserved.
  The embedded client offers the three `scan.concurrency.*` strings but deliberately omits
  `identifier.translate`; report the negotiated intersection, not the request.
- Implement `agent capabilities` from the handshake, `agent slots` from U4, and `agent status`
  from U3. Capabilities output names the single negotiated scan-concurrency mode explicitly;
  status reports the agent's grace settings and identifier strict mode, both of which change what a
  handle from a previous invocation means (U6). Status is capability-gated over the session where
  the agent supports it, and falls back to the operator HTTP endpoint only where one exists and is
  reachable — `agent-rs` has no HTTP server, so an HTTP-only implementation cannot ship.
- Make session cleanup structured and cancellation-safe. Closing a one-shot command must close the
  transport but not send a BLE disconnect.

**Done when:** status/capability/slot commands work against the released simulator, and a test proves
that closing `connect` leaves a lease while `disconnect` releases it.

### Phase 5 — Scan vertical slice

- Implement service/name wire filters, client-side RSSI filtering, duration and event limits,
  duplicate aggregation, deterministic sorting, and cancellation.
- Render human table, bounded JSON document, JSONL event stream, and quiet handle output.
- Never auto-select a device. When a later command accepts a selector rather than a handle, zero
  matches maps to 5 and multiple matches return the bounded candidate list and map to 5.
- Implement the shared selector resolver here, not in the connect slice: it is scan-backed, and
  lease-acquiring commands consume it (decision 17, U6). A connected peripheral no longer
  advertises, so selector resolution is a first-connection mechanism only — after that the echoed
  handle is the addressing path, and that split must be explicit in help text and docs.
- Include radio-state warnings when the agent advertises that capability; distinguish an empty scan
  from a known-off radio.
- Test stop delivery on normal timeout, Ctrl-C, broken pipe, limit reached, and agent error.

**Done when:** a black-box test finds the simulated HRM, honors server/client filters, refuses an
ambiguous fixture, safely renders a hostile advertised name, and resolves the same selector to the
same peripheral under both a translating and a non-translating identifier pairing.

### Phase 6 — Connection and read-only GATT operations

- Implement `connect`, `inspect`, `read`, `descriptor read`, `rssi`, and `disconnect`.
- `inspect` performs connect plus discovery and renders service/characteristic/descriptor trees,
  property bits, normalized UUIDs, and SIG names. Unknown property bits remain visible.
- `read` and descriptor reads preserve raw bytes and add optional decoding. Validate characteristic
  references before dispatch, but do not require a prior local cache or scan: the agent authorizes
  a read against the *connected* peripheral and the backend owns discovery, so a fresh process need
  not re-run `discover` before reading.
- Accept a selector wherever a handle is accepted, and report the handle to reuse in human and
  structured output. `--quiet` emits one primary value, and for `read`, `rssi`, and the encoded byte
  modes that value is the reading, not the handle — quiet handle output belongs to `connect`,
  `inspect`, and `scan`, where the handle *is* the result. A stale or foreign handle must produce
  the U6 diagnostic rather than a bare device-not-found.
- Capability-gate descriptor and RSSI operations before dispatch. Keep real-agent descriptor tests
  separate because the simulator does not model them.
- Make disconnect idempotent from the user's perspective while still reporting a definitive agent
  failure. Prominently print held-lease guidance after lease-acquiring human commands; never add it
  to JSON stdout.

**Done when:** simulator tests cover discover, battery decode, raw preservation, RSSI, separate
invocations across a realistic pause, explicit release, and simulated unsolicited disconnect.

### Phase 7 — Write safety and indeterminate outcomes

- Parse one explicit input source, enforce absolute payload bounds, and canonicalize the target.
- Check policy in two stages, because a selector-addressed write cannot know its device handle until
  a scan has resolved it, and that scan needs the transport. Stage one runs before any transport is
  opened and evaluates everything that is knowable then: read-only mode, payload size, write type,
  service and characteristic rules, and the endpoint. A target that no rule could ever permit is
  refused here, so the common mistake costs no radio work. Stage two runs after resolution and
  immediately before dispatch, re-evaluating the full rule against the concrete device handle and
  taking the rate-ledger reservation. No write is dispatched without passing stage two, and a
  stage-two refusal releases nothing to the radio.
- Default to read-only. A command-line override may select a less restrictive configured profile,
  but no hidden `--force` bypass exists.
- Dispatch writes through a session whose `retryPolicyFor` returns `RetryPolicies.None` for every
  non-idempotent op (decision 18) — `RemoteGattClient.write` has no retry parameter to pass — and
  treat any per-attempt timeout as subordinate to the command deadline. Assert this with a fake
  gateway that counts frames, not by inspecting the embedded client's default, since that default is
  upstream-derived and can change during a protocol sync.
  Failures before invoking the write are definitive; once dispatch has been attempted,
  map timeout or transport loss conservatively to 8 because the current SDK cannot prove that the
  frame did not reach the agent. A definitive agent/GATT refusal remains exit 1 or 3 as appropriate.
- Record an audit event containing timestamp, endpoint identifier, client ID, device, operation,
  GATT reference, write type, payload length, duration, and result. Payload bytes and credentials are
  redacted unless an explicit debug policy permits payload logging.
- Verify both with-response and without-response properties and document the latter's lack of ATT
  acknowledgement. Never blind-retry either.
- Once U7 exists, add a black-box test showing the agent refuses a disallowed write even when a raw
  SDK client bypasses the CLI policy.

**Done when:** allowed, read-only, empty-allowlist, wrong-characteristic, oversized, rate-limited,
definitive failure, and indeterminate outcomes all have distinct tests and output.

### Phase 8 — Bounded notification streams

- Implement `observe` using `RemoteGattClient.observe`, connect first, and collect under one overall
  count/timeout policy.
- Emit events as they arrive in JSONL; buffer only up to the configured maximum for JSON/human
  summary modes. Define and test behavior when the buffer limit is reached.
- On count, timeout, cancellation, broken pipe, or SIGINT, cancel collection so the embedded client issues
  `observe.stop`; then close the transport without releasing the peripheral lease.
- Watch connection-state events and report a simulated unsolicited disconnect with the agent's
  structured reason rather than allowing the stream to look like a normal timeout.
- Test reconnect/reconciliation within the overall deadline and ensure sequence numbers never
  repeat even if the embedded client replays a subscription.

**Done when:** ten simulated heart-rate notifications arrive as valid JSONL, a disconnect is
explained, and no subscription remains after each termination path.

### Phase 9 — Black-box acceptance and real-hardware validation

- Launch the released simulated agent on a random loopback port with named client and operator
  credentials and transport grace set to the intended value.
- Run the packaged CLI as a subprocess. Do not call command classes in-process for acceptance tests;
  process boundaries are the feature under test.
- Add fixtures for ambiguous devices, hostile names/control characters, disconnect-after-connect,
  denied writes, malformed values, and two client identities contending for one peripheral.
- Run the cross-invocation scenarios against every reference agent with the same opaque-handle
  client configuration used in release binaries. Include a selector-based fallback assertion for a
  handle that the agent does not retain across process boundaries (U6).
- Save command/output evidence as CI artifacts and validate JSON/JSONL with the checked-in schemas.
- Put the unchanged smoke script in `examples/heart-rate-smoke.sh`. Run it against the four
  required desktop pairings (macOS/Linux CLI × JVM/macOS/Rust agent); record agent versions, host,
  commands, results, grace timing, stream cleanup, and physical connection counts.

**Done when:** all twelve MVP acceptance scenarios pass in CI where simulatable, the hardware script
passes on the required hosts, and known backend differences are explicit gates rather than relaxed
assertions.

### Phase 10 — Documentation, completion, distribution, and Skill

- Complete command reference pages with examples for every output mode and exit code, plus exact
  server-side versus client-side filter notes.
- Document configuration precedence, secure token sources, TLS/proxy setup, stable client identity,
  lease cleanup, operator-status credentials, advisory versus enforced policy, and code-8 recovery.
- Generate bash, zsh, and fish completions from the command model and package them in the release.
- Publish versioned zip/tar distributions, fat JAR, checksums, SBOM, and release notes. Add a clean
  installation smoke test that uses only released artifacts.
- Only after flags and output schemas are frozen, implement `skills/remoteble` from
  [agent-skill.md](agent-skill.md). Test its bring-up, diagnostic-report, BLE/serial-correlation,
  indeterminate-write, hostile-name, and cleanup procedures against the packaged CLI.

**Done when:** a new user can install, configure, complete acceptance scenarios 1-9, and release the
lease using documentation alone; the Skill uses no unpublished or host-specific behavior.

## Command-to-core map

| Command | Core operation | Protocol/management primitive | Lease after success |
|---|---|---|---|
| `agent status` | `GetAgentStatus` | `agent.status` over the authenticated session; no HTTP fallback | none |
| `agent capabilities` | `GetCapabilities` | session handshake | none |
| `agent slots` | `GetSlots` | initial `SlotState` | none |
| `scan` | `ScanDevices` | `RemoteScanSource.advertisements` | none |
| `connect` | `ConnectDevice` | `RemoteGattClient.connect` | held |
| `inspect` | `InspectDevice` | `connect` + `discover` | held |
| `read` | `ReadCharacteristic` | `connect` + `read` | held |
| `write` | `WriteCharacteristic` | policy + `connect` + `write` | held |
| `observe` | `ObserveCharacteristic` | `connect` + bounded `observe` | held |
| `descriptor read` | `ReadDescriptor` | capability + `connect` + `readDescriptor` | held |
| `rssi` | `ReadRssi` | capability + `connect` + `readRssi` | held |
| `disconnect` | `DisconnectDevice` | `RemoteGattClient.disconnect` | released |
| `session --jsonl` | persistent command engine | typed JSONL records, async stream IDs | held until explicit disconnect/grace |
| `shell` | persistent command engine | human frontend; process-level pipes only | held until explicit disconnect/grace |
| `config show/validate` | config services | no agent unless `--connect` | none |
| `version` | build metadata | none | none |

## Test layers

1. **Pure unit tests:** UUID normalization, byte codecs, decoders, property bits, bounds, policy,
   rate ledger, config precedence/redaction, hostile text, error classification, schema models.
2. **Core contract tests:** every operation against `RemoteBleGateway` fakes, including cancellation,
   retries, event ordering, indeterminate writes, and lease-preserving cleanup.
3. **CLI tests:** Clikt parsing, usage errors, incompatible output options, stdin/stdout/stderr,
   broken pipes, signals, terminal color, and golden human output.
4. **Protocol-client integration tests:** embedded client against the released simulated agent, including
   handshake capabilities, disconnect events, subscriptions, and two client IDs.
5. **Black-box process tests:** packaged executable across separate invocations, with JSON Schema
   validation and exact exit-code assertions.
6. **Hardware checks:** the unchanged example script across the desktop 2×2 matrix. Mobile and
   Raspberry Pi checks are outside the v0.1 gate.

Coverage percentage is a diagnostic, not the release gate. The gate is behavioral coverage of each
command, every exit code, every policy refusal, every stream termination path, and all acceptance
scenarios.

## Acceptance-scenario traceability

| MVP scenario | Primary automated evidence |
|---:|---|
| 1 | agent status/capabilities/slots black-box tests |
| 2 | bounded simulator scan |
| 3 | exact selection plus zero/multiple-match fixtures |
| 4 | inspect GATT-tree golden output |
| 5 | battery `uint8` read with raw representations |
| 6 | ten notification JSONL records and schema validation |
| 7 | allowed write plus read-only/non-allowlisted refusals |
| 8 | `dropAfterMs` connection event and observe failure explanation |
| 9 | Skill-driven diagnostic report fixture with exact command evidence |
| 10 | subprocess sequence with 30-second pauses, one-physical-connect, forced-translation pairing |
| 11 | hostile-name/control-character fixture and no follow-on operation |
| 12 | two principals/client IDs and holder-bearing lease denial |

Scenario 10 is the one to read carefully. Run in the default CI pairing it proves the grace window
and nothing about handle addressing, because translation is off in exactly that configuration; the
forced pairing is what makes it evidence for the design rather than for the runner.

## Suggested pull-request sequence

Keep each change independently reviewable and green:

1. Contract clarifications, sibling-document corrections, and upstream issues U1-U7.
2. Gradle/module/executable skeleton.
3. Versioned models, schemas, output sink, and error mapper.
4. Configuration, stable client ID, and policy engine.
5. Session adapter plus capabilities.
6. Status and slots after U3/U4.
7. Scan vertical slice and hostile-input fixtures.
8. Connect/inspect/read/disconnect vertical slice and state-model test.
9. Descriptor read and RSSI capability paths.
10. Write policy, audit, and indeterminate outcome handling.
11. Observe stream and disconnect/reconciliation behavior.
12. Full simulator acceptance suite and example script.
13. Packaging, completions, CI/release workflow, and installation docs.
14. Portable Agent Skill and its workflow tests.

U2 gates the hardware half of the state model — desktop configurability already exists, so
simulator work is unblocked and the desktop defaults are now covered. U6 gates device
addressing on every host pairing the demo uses, and its client-side half (decision 17) should land
with the scan and connect slices whether or not the upstream half is accepted. U3/U4/U5 gate three
documented command behaviors, and U7 gates claiming write enforcement rather than an advisory
guardrail. Other CLI work can proceed in parallel, but no release should silently omit or weaken
those contracts.

## Release gates

- No dependency on a sibling checkout, `mavenLocal`, a local radio, or any artifact download at
  runtime. Network access to the configured agent endpoint is the product; nothing else is fetched.
- All structured output validates against schema v1; stdout contains result data only.
- Every command has bounded resource use, deterministic exit behavior, and cancellation cleanup.
- Tokens and payload contents are absent from default output, logs, stack traces, and CI artifacts.
- Separate invocations resume the same lease; explicit `disconnect` releases it.
- A handle echoed by a lease-acquiring command addresses the same peripheral from a later process on
  every supported client/agent host pairing, or the CLI refuses with the stale-handle diagnostic and
  a selector remedy — never with a bare device-not-found.
- Non-idempotent operations are never automatically retried and ambiguous completion returns 8.
- Device-derived text is escaped, bounded, and never treated as instruction.
- Simulator acceptance passes from the packaged artifact, not Gradle classpaths.
- The same documented smoke script passes across macOS/Linux CLI × JVM/macOS/Rust agents.
- The CLI-side policy is described as advisory; the agent-side per-principal policy is independently
  verified with raw-client denied-write coverage.
