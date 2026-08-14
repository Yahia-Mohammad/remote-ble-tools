# Troubleshooting

Diagnose from the exit code first, then the `errorKind` in the stderr envelope. Together they say
whether a thing is broken, busy, or simply not available here — three situations that need
completely different responses.

## By exit code

| Code | Name | Usual cause | Action |
|---|---|---|---|
| 2 | Usage | Wrong flags or arguments | Fix the command. Retrying it unchanged cannot help |
| 4 | Authentication | Token missing, wrong, or lacking operator scope | Check the token environment variable. Ask a human; do not guess credentials |
| 5 | Not found | Stale handle, characteristic absent, or an ambiguous selector | Re-scan for a fresh handle; `inspect` to confirm the characteristic exists |
| 6 | Busy | Another client holds the lease, or no free slots | Report the holder from the envelope. Wait; do not poll aggressively |
| 7 | Unsupported | Capability absent, or policy refused | **Permanent.** Check `agent capabilities`. Never retry, never work around |
| 8 | Indeterminate | Reply lost after a write was dispatched | **Never retry.** Read back, or escalate. See safety.md |
| 9 | Retryable | Transport, timeout, radio off | Retry with backoff. If it persists, check the agent is reachable |
| 1 | Failure | Anything else | Read the message |

## By error kind

The agent's `errorKind` distinguishes transient from permanent, which the exit code alone sometimes
cannot.

**Will not change on retry:**

| Kind | Meaning |
|---|---|
| `UNKNOWN_DEVICE` | The agent has never seen this handle. Re-scan |
| `CHARACTERISTIC_NOT_FOUND` | Not in the GATT table. The table will not grow on retry |
| `GATT_ERROR` | A GATT-layer protocol or permission error |
| `INVALID_REQUEST` | Malformed, or exceeds a published limit |
| `UNSUPPORTED` | This agent does not have the capability |
| `POLICY_DENIED` | Agent-side write policy refused this principal |
| `INCOMPATIBLE_PROTOCOL` | No mutually supported wire version |

**May succeed later:**

| Kind | Meaning |
|---|---|
| `CONNECTION_FAILED`, `DISCONNECTED`, `NOT_CONNECTED` | Link setup or loss; reconnect |
| `READ_FAILED`, `WRITE_FAILED` | Momentary radio failure — but check idempotency before retrying a write |
| `NO_CONNECTION_SLOT` | Agent at capacity; check `agent slots` |
| `PERIPHERAL_BUSY`, `AGENT_BUSY` | Someone else has it |
| `SCAN_UNAVAILABLE` | Another logical scan holds `single` mode |
| `TIMEOUT`, `TRANSPORT_LOST` | Network or timing |
| `RADIO_OFF` | The agent host's Bluetooth is switched off — a human has to turn it on |

## Specific symptoms

**Everything fails with exit 9.** The agent is not reachable. Check the endpoint (`--endpoint`, or
`agent.endpoint` in config, default `ws://127.0.0.1:8080/agent`), that the agent process is running,
and that the network path allows a WebSocket.

**Exit 4 on every command.** The token is missing or wrong. The CLI reads it from the environment
variable named by `agent.tokenEnvironmentVariable`, `REMOTE_BLE_TOKEN` by default. Never pass a
token as a command-line argument — it would be visible in `ps` and shell history.

**`rssi` or `descriptor read` exits 7 but the command clearly exists.** The agent does not advertise
that capability. Check `agent capabilities`: `rssi` needs `rssi`, `descriptor read` needs
`descriptors`. The btleplug/JVM backend has no connected-RSSI API, so this is expected there, not a
fault.

**A write exits 7 with no `errorKind`.** The refusal was local — `readOnly: true`, or no matching
`policy.writeRules` entry. With `errorKind: POLICY_DENIED` it was the agent's decision. Either way,
ask a human rather than changing policy.

**A write exits 7 mentioning enforcement.** `agent status` reports
`writePolicyEnforced: false`; the CLI refuses to dispatch writes to an agent that will not enforce
them. This needs an agent-side configuration change by whoever runs it.

**A handle that worked ten minutes ago now exits 5.** Handles are opaque and only as durable as the
agent's knowledge of the device. Re-scan and use the fresh handle; do not construct or edit handles.

**Two devices with the same name, and selection fails.** `--name` and `--service` select only when
exactly one device matches; otherwise the CLI refuses with exit 5 rather than guessing:

```
{"exitCode":5,"message":"Selector is ambiguous; 2 devices matched. Run scan and use a handle."}
```

Do what it says: scan with `--jsonl`, show the candidates with their handles and RSSI, and ask which
one. Never pick the strongest signal.

**`observe` refuses to start.** It requires `--count` or `--timeout`. `--unbounded` needs
`policy.allowUnboundedStreams: true`, which is a decision for a human, not a way around the error.

**`slow-consumer` closes a stream.** stdout was not being drained fast enough and the stream's
256-record queue filled. Read continuously rather than in batches; redirect to a file if the
consumer does other work between reads.

**Config file rejected at startup.** Unknown keys are errors, not warnings — this is deliberate, so
a typo cannot silently disable a guardrail. `remoteble config validate` checks a file without
contacting an agent, and names the offending key and the accepted set.

**Warnings about SLF4J on stderr.** Harmless noise from the JVM build's logging framework. Parse
stdout; the native binary does not produce them.
