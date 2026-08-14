# The JSONL session protocol

`remoteble session --jsonl` keeps one authenticated transport open and answers schema-versioned JSON
records on stdin. It is the interface to reach for when driving many operations, or when a stream
has to run while other commands continue.

## Input

One JSON object per line. Every field is required and unknown fields are rejected — a typo is a
loud error rather than a silently ignored argument.

```json
{"schemaVersion":1,"id":"c1","command":"read","arguments":{"handle":"sim-hrm-1","serviceUuid":"180f","characteristicUuid":"2a19"}}
```

| Command | Arguments |
|---|---|
| `agent.status` | none |
| `agent.slots` | none |
| `read` | `handle`, `serviceUuid`, `characteristicUuid` |
| `write` | those three, plus `writeType` and exactly one of `hex`/`base64`/`text` |
| `scan` | optional `serviceUuid`, `name`, `count`, `timeout` |
| `observe` | the three, plus `count` and/or `timeout`, or `unbounded` |
| `stream.stop` | `streamId` |
| `session.close` | none |

`id` is yours to choose: any non-empty string up to 128 bytes, unique within the session. It is the
only way to match a reply to a request.

## Output

One record per line, matching `schemas/session-output-v1.json`. `sequence` is monotonic across the
whole session and `timestamp` is UTC.

```json
{"schemaVersion":1,"type":"session.ready","sequence":1,"id":"","data":{"sessionId":"s-1","capabilities":["agent.status","rssi","slots","write.policy"],"operatorScope":false}}
{"schemaVersion":1,"type":"command.accepted","sequence":2,"id":"c1","data":{"command":"read"}}
{"schemaVersion":1,"type":"command.result","sequence":9,"id":"c1","data":{"hex":"64","base64":"ZA==","length":1}}
```

Record types: `session.ready`, `command.accepted`, `command.result`, `command.error`,
`stream.started`, `stream.event`, `stream.closed`, `session.closed`.

**Check `session.ready` before issuing anything capability-gated.** Its `capabilities` array is the
negotiated intersection with this agent, and it is cheaper to read than to discover through a
failure.

## Two behaviours that catch people out

**Results arrive out of order.** Commands run concurrently. In this real transcript, `c1` was sent
first but `c2` answered before it:

```json
{"type":"command.result","sequence":7,"id":"c1","data":{"agentInfo":"RemoteBLE Agent 0.11.0 ...
{"type":"command.result","sequence":9,"id":"c2","data":{"hex":"64","base64":"ZA==","length":1}}
```

Correlate by `id`. Never by position, and never by assuming the next result belongs to the last
command sent.

**`session.close` is answered immediately**, before in-flight work finishes:

```json
{"type":"command.result","sequence":5,"id":"c4","data":{"closed":true}}
{"type":"stream.event","sequence":8,"id":"c3","streamId":3,"data":{"hex":"003f",...}}
{"type":"stream.closed","sequence":11,"id":"c3","streamId":3,"data":{"count":2,"reason":"count"}}
{"type":"session.closed","sequence":12,"id":"","data":{"sessionId":"s-1"}}
```

The session drains in-flight commands rather than cutting them off, so sending `session.close` is
safe — but do not stop reading when you see its result. Read until `session.closed`, which is the
last record.

## Streams

`scan` and `observe` return a `streamId` on `stream.started`, then `stream.event` records, then
exactly one `stream.closed` carrying a count and a reason:

| Reason | Meaning |
|---|---|
| `count` | Reached the requested number of events |
| `timeout` | Reached the deadline |
| `complete` | The source ended on its own |
| `stopped` | You sent `stream.stop` |
| `error` | Failed; a `command.error` precedes it |
| `slow-consumer` | You were not reading fast enough; see below |

Stop a stream by ID with `stream.stop`. You must read the `streamId` from `stream.started` — it is
assigned by the session, not chosen by you.

**Each stream has a 256-record queue.** A consumer that stops reading gets that one stream closed
with `slow-consumer`, preceded by a `command.error`; other streams and command replies keep flowing
in their own queues. If you see it, you are not draining stdout fast enough — read continuously
rather than in batches between other work.

## Shutdown

Any of these ends the session cleanly: `session.close`, EOF on stdin, a signal, transport give-up,
or a broken output pipe. Active streams are cancelled and best-effort BLE stop operations are
issued, so the peripheral is not left subscribed.

## Errors

`command.error` carries the same exit-code contract as the one-shot CLI, plus the agent's error kind
when there is one:

```json
{"type":"command.error","id":"c2","data":{"exitCode":7,"message":"write not permitted for this principal","errorKind":"POLICY_DENIED"}}
```

A rejected record does not end the session — a malformed line produces one `command.error` and the
session continues. Note that `session --jsonl` exits 0 even when it never reached the agent; the
failure is reported inside the envelope, so check record contents rather than only the process exit
code.
