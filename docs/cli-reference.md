# CLI reference

The CLI is a programmatic API with a human-readable default, not a human tool with a JSON escape
hatch. Output shapes are versioned and treated as a compatibility surface.

## Command surface

```text
remoteble
├── agent
│   ├── status              connected clients, held leases, remaining grace
│   ├── capabilities        negotiated capability set of this agent
│   └── slots               connection slots in use / available
├── scan
├── inspect                 connect + discover, print the GATT tree
├── connect
├── disconnect
├── read
├── write
├── observe
├── report                  recent local audit records and results
├── session --jsonl
├── shell [--jsonl]
├── descriptor
│   └── read
├── rssi
├── config
│   ├── show
│   └── validate
├── skills
│   ├── install             install the embedded skill locally
│   └── doctor              verify installed skill copies
└── --version
```

Deferred to later versions: `run` (recipes), `descriptor write`, `pair`, `unpair`, and
connection-parameter commands. Reasons in [`mvp-scope.md`](mvp-scope.md#deferred-and-why).

`session --jsonl` is the coding-agent interface. `shell` is a small interactive client, not an OS
shell: it supports quoting, foreground/background streams, `jobs`, and `stop`, but no expansion,
redirection, subprocesses, or internal pipelines. Pipe the complete `session --jsonl` or
`shell --jsonl` process when composing with other tools.

### Install the Agent Skill

```bash
remoteble skills install
remoteble skills doctor
remoteble skills install --target codex --scope project --project-dir /work/device-firmware
remoteble skills install --target android-studio --scope project
```

These commands are local-only and do not require an endpoint, token, or network access. `install`
defaults to user-scope `auto`; `doctor` returns nonzero unless every selected copy is current. See
[`agent-skill.md`](agent-skill.md#installation-without-mcp) for locations and replacement rules.

## Core workflows

### Discover nearby devices

```bash
remoteble scan --duration 5s
remoteble scan --duration 5s --jsonl
```

```bash
remoteble scan \
  --duration 10s \
  --service 180d \
  --minimum-rssi -75 \
  --json
```

Service and exact-name filters are pushed to the agent. `--minimum-rssi` is local output filtering,
so it does not reduce radio work.

### Inspect a peripheral

```bash
remoteble inspect DEVICE_HANDLE
```

For a first connection, `connect`, `inspect`, `disconnect`, and `rssi` can resolve one exact
advertised name or service in the same session:

```bash
remoteble connect --name "Heart Rate Sensor"
```

Selectors must resolve to exactly one device; otherwise the CLI refuses the operation and asks for
a handle from `scan`.

Connects and discovers as needed, prints the GATT tree with SIG UUIDs resolved to names, and
**leaves the peripheral connected and leased** — see [`state-model.md`](state-model.md#decision).

### Read a characteristic

```bash
remoteble read DEVICE_HANDLE \
  180f 2a19
```

Raw representations stay available through `--output hex`, `--output base64`, or `--output raw`.
Structured output always includes hex, Base64, and byte length.

### Write a characteristic

```bash
remoteble write DEVICE_HANDLE \
  180d 2a39 \
  --hex 01 \
  --write-type with-response
```

The payload may instead be supplied by exactly one of `--base64`, `--text`, or explicitly encoded
stdin (`--stdin hex|base64|text`). The write type is always explicit: `--write-type` is required
and takes `with-response` or `without-response`. The CLI performs local policy
checks and then refuses the operation unless the agent reports `writePolicyEnforced=true`; a
dispatched write with an uncertain reply exits 8 and is never retried.

Writes require an explicitly enabled policy. See [`safety-model.md`](safety-model.md).

### Produce a diagnostic report

```bash
remoteble report --limit 100 --json
```

The report contains recent, already-redacted audit records in chronological order. It is local-only:
it does not contact the agent, and it never includes bearer credentials or write payload bytes.

### Collect notifications

```bash
remoteble observe DEVICE_HANDLE 180d 2a37 \
  --count 10 \
  --timeout 30s \
  --jsonl
```

Ends at the count or the timeout, whichever comes first. One of the two is mandatory unless
`--unbounded` is passed explicitly.

### Check agent state

```bash
remoteble agent status
remoteble agent capabilities
remoteble agent slots
```

`agent capabilities` matters more than it looks: capability negotiation is how the CLI knows
whether an operation is supported by the agent in front of it, and it is what turns "unsupported"
into a clear message rather than a failed call. Both the human docs and the Skill should treat it
as the first step against an unfamiliar agent.

### Persistent session

See [`session-protocol.md`](session-protocol.md) and the versioned schemas in `schemas/` for the
machine protocol. A session keeps one transport open, tags every record with a UTC timestamp and
monotonic sequence, and stops asynchronous streams by ID.

### Recipes (deferred)

A later version can support declarative procedures:

```yaml
schemaVersion: 1

device:
  service: "180d"
  namePrefix: "Test HRM"

steps:
  - connect
  - discover
  - read:      { service: "180f", characteristic: "2a19", as: battery }
  - observe:   { service: "180d", characteristic: "2a37", count: 10, timeout: 30s, as: measurements }
  - disconnect

assertions:
  - battery.value > 10
  - measurements.count == 10
```

```text
remoteble run heart-rate-smoke-test.yaml     # not implemented
```

`remoteble run` **does not exist today** — the block above is a sketch of a proposed interface, not
a command to try. This is the hardware-in-the-loop CI story, and it is deferred on purpose: it is a second language
to design, and the shell already expresses most of it. Build it when scripts in `examples/` prove
repetitive, not before.

## Output contract

### Modes

- Default — human-readable tables and summaries.
- `--json` — one bounded JSON document.
- `--jsonl` — one event per line, for streams.
- `--quiet` — a single primary identifier or value, for `$( )` capture.
- `--output MODE` — any of `human`, `json`, `jsonl`, `hex`, `base64`, `raw`, `quiet`.

`hex`, `base64`, and `raw` are reachable only through `--output`, not as bare flags: `--hex` and
`--base64` already name write payload sources on `remoteble write`, and one spelling cannot mean
both an input encoding and an output mode. Only one output mode may be selected per invocation.

### Discipline

- Result data on stdout; logs, warnings, progress on stderr.
- JSON shapes carry `schemaVersion`.
- Streaming events carry timestamps and sequence numbers.
- Documented, stable exit codes.
- Streams require a timeout, a count, or an explicit `--unbounded`.
- Tokens and authorization headers never appear in output, including in error messages and
  `config show`.

The default client ID is stored once at `~/.config/remoteble/client-id` (or
`REMOTE_BLE_CLIENT_ID_FILE`) with owner-only permissions. Set `REMOTE_BLE_CLIENT_ID` or
`agent.clientId` to use an explicitly validated 8–128-byte identity.

### Examples

Every one-shot envelope matches [`result-envelope-v1.json`](../schemas/result-envelope-v1.json):
`schemaVersion` and `type` at the top level, the command's payload under `data`, plus `timestamp`
and `sequence` in `--jsonl`. The envelopes below are emitted on one line; they are wrapped here for
reading.

`remoteble read dev_42 180f 2a19 --json` — byte values always carry both encodings and a length.
There is no decoded or typed rendering in v0.1:

```json
{
  "schemaVersion": 1,
  "type": "read",
  "data": { "hex": "55", "base64": "VQ==", "length": 1 }
}
```

`remoteble observe dev_42 180d 2a37 --count 10 --jsonl` — one record per notification. The envelope
`sequence` counts output records; the `sequence` inside `data` counts notifications on this stream:

```json
{
  "schemaVersion": 1,
  "type": "observe.notification",
  "timestamp": "2026-08-04T14:20:31.417Z",
  "sequence": 7,
  "data": {
    "sequence": 7,
    "timestamp": "2026-08-04T14:20:31.417Z",
    "handle": "dev_42",
    "serviceUuid": "0000180d-0000-1000-8000-00805f9b34fb",
    "characteristicUuid": "00002a37-0000-1000-8000-00805f9b34fb",
    "value": { "hex": "0052", "base64": "AFI=", "length": 2 }
  }
}
```

`remoteble scan --jsonl` — one record per advertisement:

```json
{
  "schemaVersion": 1,
  "type": "scan.result",
  "timestamp": "2026-08-04T14:20:29.004Z",
  "sequence": 1,
  "data": {
    "handle": "dev_42",
    "name": "Heart Rate Sensor",
    "rssi": -58,
    "serviceUuids": ["0000180d-0000-1000-8000-00805f9b34fb"],
    "manufacturerData": { "76": "0215" }
  }
}
```

Failures are envelopes too, written to stderr with `type` `error`:

```json
{
  "schemaVersion": 1,
  "type": "error",
  "data": { "exitCode": 6, "message": "peripheral is busy", "errorKind": "PERIPHERAL_BUSY" }
}
```

`session --jsonl` and `shell --jsonl` use a different, richer envelope — see
[`session-protocol.md`](session-protocol.md) and
[`session-output-v1.json`](../schemas/session-output-v1.json).

### Exit codes

Distinct codes matter here more than usual: an agent's retry decision depends on telling "the
operation failed" from "the operation may have succeeded and the reply was lost."

| Code | Meaning |
|---|---|
| 0 | Success |
| 1 | Generic failure |
| 2 | Usage / argument error |
| 4 | Authentication or handshake rejection |
| 5 | Device not found, or ambiguous selection |
| 6 | Lease denied — held by another principal |
| 7 | Operation unsupported by this agent's capabilities |
| 8 | Indeterminate non-idempotent operation (a reply may have been lost) |
| 9 | Retryable transport or transient agent failure |

Codes 8 and 9 are deliberately separate from code 1. See
[`safety-model.md`](safety-model.md#non-idempotent-operations-must-be-explicit).

## Configuration

Environment variables, a configuration file, and command-line overrides.

The default token source is the configured environment-variable name. For an ephemeral secret,
`--token-stdin` reads one UTF-8 token (maximum 8 KiB) from standard input; the token is never
rendered, written to configuration, or included in errors. A future write-payload stdin mode must
refuse `--token-stdin`, so one input stream is never interpreted as both secret and payload.

```yaml
schemaVersion: 1

agent:
  endpoint: "ws://127.0.0.1:8080/agent"
  tokenEnvironmentVariable: "REMOTE_BLE_TOKEN"
  clientId: "developer-laptop-cli"    # load-bearing — see state-model.md

defaults:
  scanDuration: "5s"
  operationTimeout: "20s"
  output: "human"

policy:                                # advisory — see safety-model.md
  readOnly: true
  maximumWriteBytes: 64
  maximumNotificationCount: 1000
  allowUnboundedStreams: false
  maximumWritesPerWindow: 60
  writeRateWindow: "60s"
```

Unknown keys are rejected rather than ignored, so a typo is a startup failure and not a silently
disabled guardrail. `remoteble config validate` checks a file without contacting an agent. There is
no descriptor-write surface to enable: `descriptor read` is the only descriptor operation.

Resolution order:

```text
command-line option → environment variable → selected configuration profile → defaults
```

Secrets come from environment variables, stdin, or an OS credential store — never literal
command-line arguments (visible in `ps` and shell history) or committed configuration.

`agent.clientId` is not a label. It is the lease-resumption key, and both changing it and sharing
it have consequences — see [`state-model.md`](state-model.md#the-client-key-is-load-bearing).
`remoteble config validate` should warn when it appears copied between machines.
