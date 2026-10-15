# Configuration

Read this reference before creating, editing, or diagnosing RemoteBLE CLI configuration. It covers
the client-side YAML only. It does not create credentials or change the RemoteBLE agent's
authoritative policy.

## Safe editing workflow

1. Confirm the CLI exists with `remoteble --version`. This skill does not install or upgrade it.
2. Resolve the target path in this order: a user-named path, `REMOTE_BLE_CONFIG`, then
   `~/.config/remoteble/config.yaml` for an explicit first-time setup. A selected path must exist
   before validation; a missing explicit path is an error.
3. If the file exists, read it and patch only the fields the user requested. Preserve unrelated
   profiles, defaults, logging settings, and policy rules. Do not replace the whole file merely
   because a minimal example is shorter.
4. Collect the agent endpoint, the *name* of the environment variable that contains its bearer
   token, and, for a `wss://` endpoint, the agent's fingerprint. Never request, render, or persist
   the token value. See [Encrypted agents](#encrypted-agents).
5. Start a new file with `policy.readOnly: true`. Omit `agent.clientId` unless the user deliberately
   supplies a stable, machine-specific identity; the CLI otherwise creates an owner-only persisted
   identity for the machine.
6. Validate locally, then inspect the effective non-secret values:

```text
remoteble --config PATH [--profile NAME] config validate
remoteble --config PATH [--profile NAME] config show
```

Only after the operator has populated the token environment variable should `agent status` and
`agent capabilities` be used to verify connectivity and compatibility.

## Minimal read-only file

```yaml
schemaVersion: 1
agent:
  endpoint: "wss://agent.example/agent"
  tokenEnvironmentVariable: "REMOTE_BLE_TOKEN"
defaults:
  scanDuration: "5s"
  operationTimeout: "20s"
  output: "human"
  logLevel: "audit"
policy:
  readOnly: true
profiles:
  lab:
    agent:
      endpoint: "wss://lab-agent.example/agent"
```

Unknown keys are rejected. Durations accept ISO-8601 or Kotlin forms such as `5s`, and policy
counts and limits must pass `config validate`.

## Supported YAML fields

- Root: `schemaVersion`, `agent`, `defaults`, `policy`, `profiles`.
- `agent`: `endpoint`, `tokenEnvironmentVariable`, `clientId`,
  `operatorTokenEnvironmentVariable`, `fingerprint`.
- `defaults`: `scanDuration`, `operationTimeout`, `output`, `logLevel`, `logDirectory`.
- `policy`: `readOnly`, `maximumWriteBytes`, `maximumNotificationCount`,
  `allowUnboundedStreams`, `writeRules`, `maximumWritesPerWindow`, `writeRateWindow`.
- Each named profile may override the same `agent`, `defaults`, and `policy` fields. Omitted fields
  inherit from the root configuration.

Use profiles for distinct endpoints or non-secret defaults. Select one with `--profile NAME` or
`REMOTE_BLE_PROFILE`. Resolution is command-line override, environment override, selected profile,
root configuration, then built-in default.

## Environment and command-line overrides

| Setting | Environment | Command line |
|---|---|---|
| Configuration path | `REMOTE_BLE_CONFIG` | `--config PATH` |
| Profile | `REMOTE_BLE_PROFILE` | `--profile NAME` |
| Endpoint | `REMOTE_BLE_ENDPOINT` | `--endpoint URL` |
| Client identity | `REMOTE_BLE_CLIENT_ID` | `--client-id ID` |
| Agent fingerprint | `REMOTE_BLE_FINGERPRINT` | `--fingerprint sha256:HEX` |
| Scan duration | `REMOTE_BLE_SCAN_DURATION` | command-specific duration |
| Operation timeout | `REMOTE_BLE_OPERATION_TIMEOUT` | command-specific timeout where available |
| Output | `REMOTE_BLE_OUTPUT` | `--output MODE`, `--json`, `--jsonl`, or `--quiet` |
| Log level | `REMOTE_BLE_LOG_LEVEL` | `--log-level audit|debug` |
| Log directory | `REMOTE_BLE_LOG_DIR` | no flag |

The ordinary token comes from the environment variable named by
`agent.tokenEnvironmentVariable`, `REMOTE_BLE_TOKEN` by default. `--token-stdin` supplies one
ephemeral token to a one-shot command. It is unavailable for `shell` and `session`, and cannot be
combined with a write payload read from stdin. An external credential manager may populate an
environment variable, but the CLI does not read credential stores directly.

## Encrypted agents

RemoteBLE 0.14+ agents serving `wss://` present a self-signed identity, so a `wss://` endpoint needs
`agent.fingerprint`: the `fp=sha256:…` value from the agent's pairing link (`--print-pairing`, or the
QR code a phone agent shows). Copy only the host, port and `fp` into configuration; the link's
`token=` value is a secret and belongs in the token environment variable. A fingerprint on a `ws://`
endpoint is refused. Never replace a pinned fingerprint because a command failed with an identity
mismatch (exit 4); ask the user to confirm the agent's identity was deliberately reset. The Linux
native executable cannot use `wss://`; use the JVM distribution there.

`agent.operatorTokenEnvironmentVariable` names the separate operator credential when operator
status is explicitly requested; it falls back to `REMOTE_BLE_OPERATOR_TOKEN`. Ordinary and
operator credentials must be distinct. Do not configure operator scope unless the user needs and
is authorized for operator-only status.

## Local write policy

Local policy is advisory. Agent-side per-principal policy is the actual control, and the CLI refuses
to dispatch a write unless the agent reports that it enforces write policy.

Do not change local write policy merely because a write was refused. Policy authoring must be a
separate, explicit user request that names an exact endpoint, device handle, service UUID,
characteristic UUID, maximum payload size, and write type. Local rules do not support wildcards.

```yaml
schemaVersion: 1
agent:
  endpoint: "wss://agent.example/agent"
  tokenEnvironmentVariable: "REMOTE_BLE_TOKEN"
policy:
  readOnly: false
  maximumWriteBytes: 64
  writeRules:
    - endpoint: "wss://agent.example/agent"
      device: "dev_42"
      serviceUuid: "180d"
      characteristicUuid: "2a39"
      maximumBytes: 1
      withResponse: [true]
```

`with-response` maps to `withResponse: [true]`; `without-response` maps to `[false]`. Include both
booleans only when the user explicitly authorizes both modes. The endpoint and device handle match
exactly; UUIDs are normalized; the rule-level `maximumBytes` and global `maximumWriteBytes` both
apply. Never create an allow-all rule, and do not execute a device write merely because the local
configuration now permits it.
