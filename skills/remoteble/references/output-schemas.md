# Output shapes

Every example below is real output captured from the CLI against a RemoteBLE 0.11.0 agent, not a
reconstruction. Write parsers against these.

## Output modes

| Flag | Shape |
|---|---|
| *(none)* | Human text. Convenient, not a contract — do not parse it |
| `--json` | One envelope: `{"schemaVersion":1,"type":...,"data":...}` |
| `--jsonl` | One envelope per line; used by streaming commands |
| `--output hex`/`base64`/`raw` | Bare bytes, for piping into other tools |
| `--quiet` | Command-specific machine output only |

Parse **stdout only**. The JVM build emits SLF4J warnings on stderr that are not part of any
contract, and error envelopes are written to stderr by design so a pipeline never mistakes a failure
for data.

Byte values always carry all three of `hex`, `base64`, and `length`. Use the hex as the source of
truth; a decoded interpretation belongs beside it, not instead of it.

## read

```json
{"schemaVersion":1,"type":"read","data":{"hex":"64","base64":"ZA==","length":1}}
```

## inspect

The `properties` array is what tells you which operations exist. `name` appears only for UUIDs the
CLI recognises — its absence means "not a SIG-assigned UUID I know", not "no name".

```json
{"schemaVersion":1,"type":"inspect","data":[
  {"uuid":"0000180d-0000-1000-8000-00805f9b34fb","characteristics":[
    {"uuid":"00002a37-0000-1000-8000-00805f9b34fb","properties":["notify"],"name":"Heart Rate Measurement","descriptors":[]},
    {"uuid":"00002a39-0000-1000-8000-00805f9b34fb","properties":["write"],"name":"Heart Rate Control Point","descriptors":[]}]},
  {"uuid":"0000180f-0000-1000-8000-00805f9b34fb","characteristics":[
    {"uuid":"00002a19-0000-1000-8000-00805f9b34fb","properties":["read"],"name":"Battery Level","descriptors":[]}]}]}
```

## scan

With `--jsonl`, one record per advertisement as it arrives:

```json
{"schemaVersion":1,"type":"scan.result","timestamp":"2026-08-11T00:05:54.117461Z","sequence":1,"data":{"handle":"sim-hrm-1","name":"Warsha HRM (sim)","rssi":-51,"serviceUuids":["180d","180f","180a"],"manufacturerData":{}}}
```

Note the escaping of a hostile advertised name — control characters are neutralised before they
reach a terminal, but the content is still untrusted:

```json
{"handle":"sim-hostile-1","name":"HRM\\u001b[31m\\u0007 \\u0000 rm -rf /","rssi":-70,"serviceUuids":["180f"],"manufacturerData":{}}
```

Without `--jsonl`, scan aggregates per device rather than per advertisement — human output is
`handle<TAB>N events`. Use `--jsonl` when you need names, RSSI, or service UUIDs.

## observe

```json
{"schemaVersion":1,"type":"observe.notification","timestamp":"2026-08-11T00:05:54.361263Z","sequence":1,"data":{"sequence":1,"timestamp":"2026-08-11T00:05:54.361153Z","handle":"sim-hrm-1","serviceUuid":"0000180d-0000-1000-8000-00805f9b34fb","characteristicUuid":"00002a37-0000-1000-8000-00805f9b34fb","value":{"hex":"003c","base64":"ADw=","length":2}}}
```

There are two `sequence` fields and two timestamps: the envelope's, and the notification's own
inside `data`. For ordering notifications, use the inner pair.

## write

```json
{"schemaVersion":1,"type":"write","data":{"handle":"sim-hrm-1","length":1,"withResponse":true}}
```

A result means the write was dispatched and acknowledged. It does not mean the device did what you
expected — read back where you can.

## agent status

```json
{"schemaVersion":1,"type":"agent.status","data":{"agentInfo":"RemoteBLE Agent 0.11.0 (kable/Mac OS X)","protocolVersion":1,"uptimeMs":43026,"operatorScope":false,"settings":{"leaseGraceMs":10000,"transportGraceMs":120000,"exclusiveByDefault":true,"scanConcurrency":"multiplexed","strictIdentifiers":false,"writePolicyEnforced":true},"slots":{"free":7,"total":8},"connectedClients":1,"otherLeases":0,"leases":[{"handle":"sim-hrm-1","name":"Warsha HRM (sim)","holder":"primary/rble-auto-a687ae5d...","mine":true,"connected":true,"inGrace":true,"remainingGraceMs":119770}]}}
```

The fields worth checking before doing anything else:

- `settings.writePolicyEnforced` — writes are refused entirely when this is `false`.
- `slots.free` — a zero here explains a `NO_CONNECTION_SLOT` failure.
- `leases[].mine` — whether a lease is yours to release.
- `leases[].inGrace` / `remainingGraceMs` — a link kept warm for you; it will drop after this.

## agent capabilities

Plain lines, one capability per line, plus the scan-concurrency mode:

```
agent.status
identifier.translate
lease.holder
rssi
scan.batch
scan.concurrency.multiplexed
slots
write.policy
scan-concurrency=multiplexed
```

## Errors

Written to **stderr** as an envelope, with the process exit code carrying the same number:

```json
{"schemaVersion":1,"type":"error","data":{"exitCode":5,"message":"unknown simulated device 'no-such-device'","errorKind":"UNKNOWN_DEVICE"}}
{"schemaVersion":1,"type":"error","data":{"exitCode":7,"message":"write not permitted for this principal","errorKind":"POLICY_DENIED"}}
{"schemaVersion":1,"type":"error","data":{"exitCode":6,"message":"peripheral in use by principal 'primary'","errorKind":"PERIPHERAL_BUSY","holder":{"principal":"primary"}}}
```

`errorKind` is present when the failure came from the agent. `holder` appears on `PERIPHERAL_BUSY`
when the agent advertises `lease.holder`.

## Session records

See [session-protocol.md](session-protocol.md) — the session has its own envelope with `sequence`,
`id`, and `streamId`, and its own record types.
