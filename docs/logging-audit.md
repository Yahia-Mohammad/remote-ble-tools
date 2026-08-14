# Logging and audit

Every remote management or BLE operation and stream lifecycle writes a redacted JSONL
audit record. Advertisement and notification payloads are not logged. Records include
UTC time, operation/session IDs, sanitized endpoint, client/device/GATT references,
write type, payload length, duration, result, and agent error kind. Bearer credentials,
authorization headers, payload contents, notification values, and complete command lines
never appear.

Audit is always enabled and uses an advisory cross-process state lock shared with the rolling
write-rate ledger. That lock is `FileChannel.tryLock` on the JVM and `fcntl(F_SETLK)` on Native;
because POSIX record locks are owned by the process rather than the thread, the Native path also
serializes threads on an in-process mutex before taking it, so concurrent session jobs cannot share
one process's lock ownership. Ledger target keys are SHA-256 hashes rather than raw endpoint or GATT
identifiers; malformed ledger state fails closed. Failure blocks a mutation before dispatch; read-only operations may
continue with a warning. Debug logs are opt-in via `--log-level`, environment, profile,
or YAML. Files rotate by UTC day with 14 audit days and seven debug days retained and
owner-only permissions. Linux defaults to `$XDG_STATE_HOME/remoteble/logs` (or
`~/.local/state/remoteble/logs`); macOS uses `~/Library/Logs/remoteble`. Set
`REMOTE_BLE_LOG_DIR` to override.

`--log-level audit|debug` has normal precedence over `REMOTE_BLE_LOG_LEVEL`, the selected profile,
and root YAML. Debug records remain metadata-only. A write records an audit attempt before dispatch
and an outcome after a definitive reply; a failed pre-dispatch audit prevents the write.
Connection and enforcement checks run before that attempt record. After it succeeds, the
CLI issues one write frame and does not retry; a timeout or transport loss is recorded as
an uncertain outcome rather than a claim that no write occurred.
