# Contributing

Thanks for improving RemoteBLE Tools. This is a safety-oriented command-line client for real BLE
hardware, so a change is complete only when its behavior, machine-readable output, and safety
boundary agree.

## Local checks

Use JDK 17 and the checked-in Gradle wrapper. Before opening a pull request, run the focused tests
for the changed module and then the standard JVM checks:

```sh
./gradlew :core:jvmTest :cli:jvmTest :integration-tests:test formatCheck
```

Run `:integration-tests:liveAgentTest -Premoteble.agent.required=true` when changing the wire
protocol, CLI lifecycle, or integration fixtures. It starts the pinned released agent in
radio-less simulation; it is not a replacement for the real-hardware release gate.

Native changes need the matching target test. On a matching host, also run:

```sh
./gradlew :integration-tests:nativeLockHandoffTest
```

## Change expectations

- Preserve the versioned schemas in `schemas/`, stable exit codes, and the JSONL session contract.
  If a compatibility change is intentional, document it and add schema/packaged coverage.
- Keep diagnostics and audit output free of bearer credentials and write payloads. Do not relax
  local or agent-side write policy checks without explicit safety review.
- Test actual process boundaries for stream, signal, output, locking, or persistence changes; a
  unit test alone is not sufficient evidence for those behaviors.
- Do not add a second agent implementation solely for tests. The released JVM simulator is the
  normal integration peer; real-hardware validation follows
  [`docs/hardware-validation.md`](docs/hardware-validation.md).
- Keep commits focused and explain user-visible or protocol-visible behavior in the pull request.

For security-sensitive issues, follow [`SECURITY.md`](SECURITY.md) rather than opening a public
issue.
