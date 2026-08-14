# State model

BLE connections and subscriptions are long-lived; CLI invocations are short-lived. Reconciling
those two is the central design problem of this project, and getting it wrong makes the tool feel
broken in exactly the workflow it exists to serve.

## The mechanism that makes this possible

RemoteBLE's agent — not the CLI — owns connection state. A client that reconnects presenting the
same client key **resumes its leases** rather than colliding with itself, and the underlying link
is kept *warm*: no re-pair, no re-discovery. That is implemented in
[`PeripheralRegistry`](https://github.com/Yahia-Mohammad/remote-ble/blob/v0.11.0/agent/src/commonMain/kotlin/dev/warsha/remoteble/agent/PeripheralRegistry.kt).

This is precisely the primitive a stateless CLI needs. Each `remoteble` invocation opens a session
with the same client key, resumes whatever the previous invocation left open, and does its work.

## The problem: the window is 10 seconds

```kotlin
private val leaseGrace: Duration = 10.seconds,
private val transportGrace: Duration = 10.seconds,
```

Two distinct windows, both hard-coded defaults:

- **`transportGrace`** — the client's transport dropped (the CLI process exited). **This is the
  binding one for a stateless CLI.**
- **`leaseGrace`** — the BLE link dropped while the client was still connected.

They are constructor parameters on `PeripheralRegistry`, surfaced as `AgentConfig` fields in
[`AgentModule.kt`](https://github.com/Yahia-Mohammad/remote-ble/blob/v0.11.0/agent/src/commonMain/kotlin/dev/warsha/remoteble/agent/di/AgentModule.kt),
displayed read-only on the agent dashboard, and configurable through the desktop agent environment
variables/flags. The v0.1 default transport grace is 120 seconds; BLE-disconnect grace remains 10
seconds.

Ten seconds is shorter than the gap between consecutive commands in every real usage pattern:

| Caller | Typical gap between commands |
|---|---|
| Human reading output, deciding, typing | 5 s – minutes |
| Coding agent between tool calls | 15 – 60 s |
| Shell script | milliseconds ✅ |

Only the case that needs it least fits inside the window. In particular, `inspect` → *model
thinks* → `read` → *model thinks* → `write` — the exact sequence the Agent Skill will produce —
falls out of the window between almost every step, silently paying a full reconnect and
rediscovery each time. On real hardware that is seconds per command, and on a contended rig it
means the lease is released and may be taken by someone else mid-procedure.

## Decision

**One-shot commands do not disconnect.** They connect if needed, discover if needed, perform the
operation, close the transport, and deliberately leave the peripheral connected and leased. The
lease is released by an explicit `remoteble disconnect`, by an idle timeout at the agent, or by the
agent's configured grace policy. `session --jsonl` and `shell` keep one transport open for their
process lifetime; EOF/signal cancels streams while allowing the agent grace window to apply.

```text
remoteble scan      → transport opens, scans, closes. No lease.
remoteble inspect X → connects, discovers, closes transport. Lease HELD, link warm.
remoteble read X …  → transport reopens, lease RESUMES, reads, closes. Lease still held.
remoteble disconnect X → releases.
```

This makes the top-level `connect` and `disconnect` commands coherent: a connection genuinely
outlives its process, which is the only reading under which those commands mean anything.

It also makes `remoteble agent status` important rather than cosmetic — it is how a user discovers
what they are still holding.

### Consequence: this required an upstream change

The grace window had to become operator-configurable and default higher for CLI use. On the desktop
agents this is **done**: the JVM agent reads `REMOTE_BLE_TRANSPORT_GRACE_MS` and
`REMOTE_BLE_LEASE_GRACE_MS`, `agent-rs` takes `--transport-grace-ms` / `--lease-grace-ms`, and the
transport-grace default is now **120 seconds**. BLE-disconnect grace remains 10 seconds.

What is still open is the phone agents: Android and iOS expose no operator control for either
window, which is what gates the mobile half of hardware acceptance.

There is no client-side workaround for the remainder, and there never was one: the timer runs on the
agent.

### Consequence: holding is a cost on shared hardware

A longer window means a peripheral stays leased for up to two minutes after someone walks away.
On a shared rig that is real contention, and it is the direct trade for usability. Mitigations,
in order of preference:

1. `remoteble disconnect` is prominent in documentation and in the Skill's cleanup step.
2. `agent status` shows held leases and their remaining grace.
3. A lease-denied error names the current holder's principal, so contention is diagnosable rather
   than a mysterious timeout.

## Rejected alternative: a local daemon

A `remoteble session start` daemon holding the socket, with commands talking to it over a unix
socket, needs no upstream change and holds the lease exactly as long as the session is explicitly
open — strictly better semantics.

It is rejected for v0.1 because it adds a daemon lifecycle (start, discover, health, stale socket,
crash recovery, concurrent sessions) to a project whose entire thesis is that it is thin. The
grace-window change is a few lines upstream and benefits every RemoteBLE client, not just this one.

**Keep it as the documented fallback.** If shared-rig contention proves the window approach wrong
in practice, this is the answer, and the `core` module boundary should not assume otherwise.

## Command classification

| Class | Commands | Transport | Lease on exit |
|---|---|---|---|
| Stateless | `scan`, `agent status`, `agent capabilities`, `agent slots`, `config *`, `version` | Opens, closes | None taken |
| Lease-acquiring | `connect`, `inspect`, `read`, `write`, `descriptor read`, `rssi` | Opens, closes | **Held**, warm |
| Session-lifetime | `observe`, `session --jsonl`, `shell` | Held open for the command's duration | Held; released per explicit disconnect/grace |
| Releasing | `disconnect` | Opens, closes | Released |

`observe` is the well-behaved case in every design: one process holds one session and streams until
its count or timeout is reached. It needs none of the above to work.

## The client key is load-bearing

Lease resumption is keyed on the client identity sent as `X-RemoteBle-Client`, configured as
`agent.clientId`. It is not a cosmetic label:

- **Change it and you lose your lease.** A different key is a different client, so the previous
  lease stays held until its grace expires and the new invocation is denied.
- **Share it and you share an identity.** Two engineers who copy the same configuration file
  become the same client to the agent, and will silently resume — and steal — each other's
  peripherals. The bearer token selects the principal; the client key is only a reconnect key
  *within* that principal, so this is a footgun inside a team sharing one token.

Default it to something machine-derived and unique (host plus user), document it, and have
`remoteble config validate` warn when it looks copied.
