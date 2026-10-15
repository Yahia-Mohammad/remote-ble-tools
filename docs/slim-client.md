# Slim protocol client

`remoteble` embeds the small RemoteBLE client slice needed by a command-line process. It does not
depend on `dev.warsha.remoteble:client-sdk`, Kable, Koin, or a local Bluetooth implementation.

## Included

- The v1 wire models and frozen serialization discriminators in
  `core/src/commonMain/kotlin/dev/warsha/remoteble/protocol`.
- CBOR frame encoding and decoding through `kotlinx.serialization`.
- The Ktor WebSocket byte transport, session correlation and handshake, remote scan stream, and
  the remote GATT operations used by the CLI.
- The `scan.fields` capability (0.15.0): service data, Tx power, connectability and the platform's
  name on `AdvertisementDto`, decoded only after the CLI negotiates it, since `Cbor.Default` rejects
  unknown keys.
- Capability-gated management contracts: `agent.status`, immediate `slots`, `write.policy`, and
  optional `lease.holder` diagnostics. There is no CLI HTTP dashboard fallback or explicit slots
  query.
- The persistent `session --jsonl`/human `shell` operation engine and redacted audit layer.
- `wss://` with a pinned agent identity (`AgentFingerprint`, SPKI SHA-256): OkHttp on the JVM and
  Darwin on macOS, with the identity-mismatch handling of the SDK's pinned clients. `ws://` stays on
  Ktor CIO. Linux native has no engine that can pin, so it refuses `wss://`.
- Opaque `DeviceHandle` values. The CLI neither requests identifier translation nor constructs a
  platform BLE identifier.

## Excluded

- Kable `Peripheral`, `Scanner`, identifiers, advertisements, and platform workarounds.
- Any Android, Apple, JVM/btleplug, or other local-radio backend.
- Koin modules and the SDK logging facade.
- `AgentPairing` (`remoteble://` links). The CLI takes the endpoint and fingerprint separately, so
  the token in a link never lands in configuration.
- App-facing peripheral factories and APIs not called by the CLI.

The extracted sources are derived from Remote BLE Transport 0.11.0, with the TLS pinning added in
0.14, and are verified against the 0.15.0 agent by the live-agent suite. They are covered by the root
`LICENSE` and `NOTICE`. When the wire protocol changes, update the protocol models, codec vectors,
session handshake, and agent compatibility tests together. Do not copy the complete app-facing SDK
back into this repository.

## Targets

The common client and Clikt command tree compile for JVM, macOS ARM64, Linux x64, and Linux ARM64.
Native binaries are built and tested on a matching host. The JVM fat JAR is available on hosts
without a native distribution.
