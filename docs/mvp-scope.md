# MVP scope (v0.1)

## In scope

- Kotlin Multiplatform command-line application, distributed primarily as self-contained native
  macOS and Linux executables with a JVM fat JAR retained for compatibility testing.
- Endpoint, token, and client-id configuration; `config show` / `config validate`.
- `agent status`, `agent capabilities`, `agent slots`.
- Bounded `scan` with filters.
- `connect`, `inspect` (connect + discover + GATT tree).
- `read`, with format decoding alongside raw bytes.
- `write`, disabled by default and allowlist-gated when enabled.
- Bounded `observe`.
- `rssi`.
- `disconnect` — not optional, given the state model.
- Persistent machine session: `session --jsonl` with asynchronous streams and explicit stop.
- Human `shell` with foreground/background streams, `jobs`, and process-level piping through JSONL.
- Redacted persistent audit logging and opt-in debug logging with daily rotation.
- Human, JSON, JSONL, hex, Base64 output; stable exit codes.
- Simulator-backed integration tests.
- One portable `remoteble` Agent Skill.
- Installation and shell-completion documentation.

## Deferred, and why

| Item | Reason |
|---|---|
| Recipe runner (`run`) | A second language to design. The persistent session and shell already express most of it |
| Persistent local daemon | The documented fallback if grace-window tuning fails in practice — see [`state-model.md`](state-model.md#rejected-alternative-a-local-daemon) |
| `descriptor write` | Higher risk, and **the simulator does not model descriptors**, so it cannot be covered by the integration suite |
| MTU and connection-parameter commands | Same: not modelled by the simulator, therefore not testable in CI |
| `pair` / `unpair` | **Not supported by the reference agents at all.** btleplug implements neither, so these would fail against the agents users run. Not a "later" item until the per-agent capability matrix is verified — see [`safety-model.md`](safety-model.md#pairing-is-unavailable-not-merely-restricted) |
| Semantic device profiles | See [`profiles.md`](profiles.md). v1.1 at the earliest |
| Multi-agent routing, device farms | A genuinely new system, not an increment |
| MCP facade | See [`concept.md`](concept.md#on-mcp-later-if-at-all) |
| GUI | Out of scope permanently |

Note the pattern in the middle rows: **anything the simulator cannot model cannot be covered by the
integration suite**, and untested surfaces that write to hardware are the ones least suitable for
agent use. That is the rule the deferrals follow, not a general sense of risk.

## Blocking prerequisites

1. The management/write-policy worktrees must be committed and independently integrated; the final
   tools PR must not require `mavenLocal`, a sibling checkout, or unpublished artifacts.
2. The desktop agents must use the documented 120-second transport-grace default (or an explicit
   override) and prove a same-client lease resume after a 30-second cross-process pause.
3. A JVM simulated-agent fixture must be available to the packaged CLI without a local BLE adapter.

Mobile, iOS, and Raspberry Pi agents are explicitly outside the v0.1 PR gate. The release evidence
instead covers macOS and Linux CLIs against the JVM/macOS and Rust desktop agents.

## Acceptance scenarios

Against a **simulated** agent, with no Bluetooth adapter present, a human or agent can:

1. Check whether a RemoteBLE agent is ready and what it supports.
2. Scan for the simulated Heart Rate peripheral.
3. Select it unambiguously, and be refused clearly when the selection is ambiguous.
4. Discover and print its GATT tree.
5. Read and decode its battery level, with raw bytes preserved.
6. Collect ten heart-rate notifications as JSONL.
7. Perform an authorized control-point write, and be refused when writes are disabled or the
   characteristic is not allowlisted.
8. Detect and explain a simulated disconnect — the profile's `connect.dropAfterMs` produces exactly
   one unsolicited disconnect, so this is directly backed.
9. Produce a diagnostic report containing the commands run and their results.
10. **Run steps 4–7 as separate invocations with a realistic pause between them, and have the lease
    survive.** This is the state model's acceptance test; without it the design is unproven.
11. **Not be misled by a peripheral advertising a hostile name.** A simulation profile supplies the
    name; the assertion is that it appears as delimited data and changes no behaviour.
12. Contend for one peripheral from two client identities, and have the second receive a clear
    lease-denied error naming the holder rather than a timeout.

Then, against **desktop real hardware**: run the same script unchanged for the four macOS/Linux
CLI × JVM/Rust-agent pairings and record versions, commands, outputs, grace timing, and physical
connection counts.

Scenarios 10, 11, and 12 are the ones that matter most. 10 validates the one design decision that
could sink the project; 11 and 12 are things no local-radio competitor can produce at all, because
they require a scriptable peripheral and a lease model respectively. The final hardware run is the
demo.
