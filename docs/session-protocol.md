# Persistent session protocol

`remoteble session --jsonl` is the stable interface for coding agents. It keeps one
authenticated WebSocket session open until EOF, `session.close`, a signal, transport
give-up, or a broken output pipe. The human `shell` command uses the same operation
services but is not an operating-system shell.

Each input line is a version-1 object matching [`session-input-v1.json`](../schemas/session-input-v1.json):

```json
{"schemaVersion":1,"id":"cmd-1","command":"read","arguments":{"handle":"AA","serviceUuid":"180f","characteristicUuid":"2a19"}}
```

Each output line matches [`session-output-v1.json`](../schemas/session-output-v1.json).
`sequence` is monotonic for the entire session and `timestamp` is UTC. Stream records
carry `streamId`; events from different streams may interleave, while one command's
records remain causally ordered. Each stream has a 256-record queue. A saturated
consumer closes only that stream with a `command.error` followed by `stream.closed`
using reason `slow-consumer`; replies are never discarded.

The session accepts `agent.status`, `agent.slots`, `scan`, `observe`, `read`, `write`,
`stream.stop`, and `session.close`. Streams are asynchronous and should be stopped by
ID. EOF and broken pipes cancel active streams and issue best-effort BLE stop operations;
the process exits cleanly. Native launchers ignore `SIGPIPE` and both runtimes detect failed
stdout writes, so cleanup is driven by an observed broken downstream pipe. No prompt or
diagnostic is written to machine stdout.

The shell supports quoting and escaping, foreground/background streams (`&`), `jobs`,
`stop STREAM_ID`, `help`, and `exit`. It deliberately has no expansion, globbing,
redirection, child-process execution, or internal `|`. Pipe the whole `shell --jsonl` or
`session --jsonl` process when a coding agent needs a Unix pipeline. Shell stream
shutdown uses the same acknowledged `scan.stop` / `observe.stop` operations as the
machine session.

Operator scope is fixed at startup: use `session --jsonl --operator` or `shell --operator`.
The client always supplies the ordinary bearer credential and supplies the separate operator bearer
as `X-RemoteBle-Operator`; it does not create a different BLE principal. `session.ready` reports
the negotiated capabilities and whether the agent granted that scope.
