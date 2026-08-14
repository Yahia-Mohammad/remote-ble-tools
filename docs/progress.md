# Implementation progress

Last updated: 2026-08-14 — final review fixes and complete local verification passed

## Current status

**The upstream half is done.** `feat/cli-readiness` was merged into `remote-ble-public` `main` as
PR #9 and released as **`v0.11.0`** on 2026-08-10. That release carries `agent.status`,
`write.policy`, `lease.holder`, `identifierFormat` on the handshake, 120 s transport grace, and
agent-wide lease-aware slot accounting. The wire protocol version is unchanged at 1 and no
`@SerialName` discriminator moved. This CLI's embedded protocol slice matches that tag field-for-
field; the status DTOs were compared directly.

v0.1 of the CLI is **not yet releasable**, and what separates it from release is external *evidence
rather than coding work*. The packaged CLI has been exercised against that released agent: 28 live
acceptance tests pass via `:integration-tests:liveAgentTest`, which starts the 0.11.0 JVM agent
in radio-less `--simulate` mode and drives the real fat JAR through it — capability negotiation,
`agent.status` decode, scan and selector resolution, hostile names, discovery, reads, ten
notifications, a typed simulated disconnect, allowed and refused writes on both sides of the policy
boundary, lease survival across a 30-second pause, lease contention naming its holder, and the
persistent session.

That is **not** "all twelve MVP scenarios pass" — see the remaining gates for what those 28 tests do and do
not reach. Still unproven: `descriptor read` (no simulator coverage) and the four physical CLI/agent
pairings. Clean extracted-archive smoke succeeds locally on macOS and is configured on each matching
CI host; the first hosted run remains evidence to capture.

Sequencing, ownership, and the public-publishing steps are in [`release-plan.md`](release-plan.md).

### Implemented inventory

The following implementation is committed on the current branch. JVM/integration tests, packaged
live-agent acceptance, all three native archive targets, matching macOS Native tests, the live
JVM↔Native handoff, and formatting were rerun after the latest correctness repairs:

- The embedded slim client sends the ordinary bearer plus optional `X-RemoteBle-Operator`, offers
  the reconciled capability set, requests `IdentifierFormat.STRING`, and reports negotiated
  capability/operator scope in `session.ready`.
- One-shot `agent status --operator`, `session --jsonl --operator`, and `shell --operator` use
  startup-fixed operator scope and reject a non-operator result.
- `session` has concurrent command jobs, a single timestamp/sequence writer, per-stream 256-record
  queues, managed scan/observe stop-and-wait cleanup, and structured `slow-consumer` closure.
  Packaged PTY, broken-pipe, and deterministic slow-consumer delivery coverage passes.
- `shell` uses managed scan/observe streams and `stop STREAM_ID` waits for cleanup; packaged PTY
  foreground jobs, Ctrl-C, and EOF are covered.
- One-shot, session, and shell writes now use `WriteOperationService`: local exact policy and
  ledger checks, capability/status/connection preflight, attempt/submitted/outcome audit records,
  one write dispatch, and indeterminate mapping for timeout, transport loss, or cancellation. The
  mutation-attempt audit record is the boundary: cancellation *before* it — during policy,
  capability, connection, or rate checks — is definitive because no frame exists yet, and everything
  after it is indeterminate because neither the send nor the socket reports how far a cancelled
  write got. Policy-resolution/preflight, audit-failure, and exact dispatch tests pass locally; the
  complete frontend matrix tests pass.
- Status, slots, discovery, characteristic/descriptor/RSSI reads, and scan/observe now use
  `RemoteOperationService` where they are exposed by the one-shot, machine-session, and shell
  frontends. A shared bounded collector makes scan/observe deadlines normal successful completion;
  managed streams retain one audit correlation ID from acknowledged start through one acknowledged
  stop. Selector resolution and one-shot connect/disconnect lifecycle are part of the same shared
  layer.
- Protocol, timeout, authentication, and transport failures map to the same stable CLI exit
  contract in `core`, with the CLI and persistent frontends using that shared mapper.
- The local rate ledger and audit/debug files use a persistent `.state.lock` inode with a five-second
  whole-file POSIX record lock. JVM uses `FileChannel.tryLock`; Native uses `fcntl(F_SETLK)` through
  a small cinterop wrapper and retries only `EACCES`/`EAGAIN`. Because POSIX record locks are owned
  by the process rather than the thread, the Native path first serializes threads on an in-process
  mutex, so concurrent session jobs cannot share one process's lock ownership or drop it by closing
  a descriptor. The inode is never unlinked, so the kernel releases it on descriptor/process exit.
  SHA-256 target keys, corrupt-state refusal, and atomic replacement remain unchanged.
  Identity is random, persisted owner-only, and explicit copied `rble-auto-*` values emit a warning.
  JVM multiprocess audit and identity first-create races, invalid identities, permissions, retention,
  and debug fallback are covered; a matching-host live JVM↔Native handoff also passes on macOS.
- Audit records now include a random `operationId`; shared write attempt/submitted/outcome and
  shared management/read outcomes use the same identifier. Rotation, mutation failure, permissions,
  debug fallback, and multiprocess integrity tests pass; broad redaction fuzzing remains deferred.
- JVM detects `PrintStream` output errors; Native ignores `SIGPIPE` and checks `fwrite`/`fflush`.
  One-shot result data goes through that same platform primitive rather than Clikt's `echo`, which
  reports no write failure; diagnostics stay on `echo(err = true)`. A packaged broken-pipe test
  verifies the process exits cleanly.
- Session and one-shot output schemas strictly type every published discriminator. Table-driven
  goldens exercise valid and invalid data for every one-shot type, all declared session record
  unions, both stream-event forms, and real packaged output.

| Workstream | Status | Current evidence |
|---|---|---|
| Slim KMP client and packaged JVM CLI | Implemented | Embedded protocol/client, fat JAR, packaged integration harness, and formatting checks pass locally |
| Management protocol | Released in 0.11.0 | `agent.status`, scoped status, immediate global slot state, grace deadlines, safe holder disclosure, and two-credential operator scope are released in `v0.11.0` |
| JVM agent management | Released in 0.11.0 | Scoped operator status, global slots, warm resume, 120 s transport grace, and policy loading have broad JVM coverage |
| Rust agent management | Released in 0.11.0 | Status/slots/disclosure/grace parity passes 175 Rust tests plus formatting and clippy |
| Agent write enforcement | Released in 0.11.0 | Strict per-principal wildcard-capable rules, compatibility-gated `POLICY_DENIED`, and raw-client enforcement proof are released in `v0.11.0` |
| CLI write behavior | Implemented locally | One-shot, session, and shell share a single write service with explicit payload/type validation, exact local policy/rate checks after enforcement/connection preflight, audited attempt/submitted/outcome records, single-frame dispatch proof, and indeterminate outcome handling |
| CLI configuration and streams | Automated coverage complete | Command/environment/profile/default precedence, bounded observe, persistent JSONL session, stream IDs, stop delivery, and human shell are present; packaged PTY/Ctrl-C/EOF, cleanup, broken-pipe, and deterministic backpressure coverage passes |
| Local policy ledger and audit | Automated coverage complete | SHA-256-keyed rolling ledger rejects corrupt state, survives long keys, and passes JVM process contention, permissions, retention, debug fallback, multiprocess integrity, and identity-race tests; the matching macOS JVM↔Native handoff passes |
| Release packaging and CI | Automated configuration complete | JVM and all three target-specific native archives build locally and include `remoteble`, `rble`, completions, LICENSE/NOTICE, per-target SHA256SUMS, and generated SBOMs; each native CI lane runs matching core/CLI tests, the synchronized lock handoff, and its extracted archive, while the package job runs the extracted JVM archive. First hosted results remain to be captured |
| MVP acceptance scenarios | Passing locally against a live agent | 28 live tests via `:integration-tests:liveAgentTest` against the released 0.11.0 JVM agent in `--simulate` mode; scenarios 8–10 cover a typed simulated disconnect, diagnostic report, and complete cross-invocation workflow; allowed, local-policy-denied, and rate-limited writes cover all three frontends; and a raw CBOR/WebSocket client proves agent-side denied-write enforcement. The same suite is configured as a required CI job |
| Native and hardware acceptance | Partially automated | macOS JVM↔Native lock handoff and extracted-archive smoke pass locally; matching Linux execution is configured in CI, while desktop-agent 2×2 physical evidence remains |

## Correctness and integrity repairs

The four milestone review findings are repaired in the current implementation:

- **Cross-runtime state locking:** Native no longer uses `O_EXCL` sentinel ownership. Its whole-file
  `fcntl` write lock targets the same persistent inode and byte range as JVM `FileChannel.tryLock`.
  `WriteRateLedgerTest` proves JVM thread and process contention, five-second fail-closed behavior,
  forced-process-exit release, and inode reuse. `:core:macosArm64Test` passes and Linux x64/ARM64
  Native compilation succeeds. `:integration-tests:nativeLockHandoffTest` proves live JVM↔Native
  handoff in both directions on macOS. The Native helper's `ready`/`go`/`attempting` handshake proves
  it has reached the acquisition boundary before either side is asserted blocked; matching Linux
  execution is configured in CI and awaits its first hosted result.
- **Bounded stream completion:** `collectBoundedStream` is used by one-shot JSONL, session, and shell
  scan/observe paths. Deadlines now return `timeout`, stop the protocol stream in `NonCancellable`,
  and retain a zero exit rather than emitting a failure envelope. `BoundedStreamTest` covers timeout,
  count, and upstream-error behavior.
- **Write cancellation integrity:** dispatch is explicitly tracked after the mutation-attempt audit.
  A post-dispatch `CancellationException` writes an `indeterminate`/`CANCELLED` outcome and maps to
  exit 8 without retry. `WriteOperationServiceTest` verifies exactly one submitted frame and no
  definitive completion for that path.
- **Accurate stream-start auditing:** start audit is invoked only from the scan/observe subscription
  after a successful protocol reply. Terminal lifecycle records and stop delivery are idempotent.
  `ManagedStreamAuditTest` verifies no pre-acknowledgement `started`, one correlated start/error
  record, one terminal record, and one stop request.

## Second-review repairs

A follow-up review of this worktree found eleven further defects. All are repaired:

- **No command can hang on an unreachable agent.** `capabilities()` and `slots()` awaited a
  `StateFlow` that is only populated by agent traffic and is reset to `null` on reconnect, so
  `agent capabilities`/`status`/`slots`, `rssi`, and `descriptor read` waited forever instead of
  exiting. `awaitNegotiated` now bounds every such wait by the operation deadline and short-circuits
  `GAVE_UP`/`INCOMPATIBLE_PROTOCOL`; `awaitReady` does the same so `session`/`shell` fail as promptly.
  `NoAgentExitContractTest` runs the packaged CLI against a closed port under a process deadline.
- **Native state locking excludes threads, not just processes.** POSIX record locks are owned by the
  process, so concurrent session jobs all entered the critical section and the first to finish
  released the lock for the rest by closing its descriptor. A process-wide `pthread` mutex now wraps
  the whole open/lock/close sequence. `FileLockNativeTest` fails without it.
- **Session ids are released on stream setup failure.** A `scan`/`observe` that failed before its
  stream job existed leaked its id, so the caller could never reuse it for the session's lifetime.
- **Session arguments are typed.** `JsonNull.content` is the string `"null"`, so `{"handle": null}`
  became a device handle named `null` and was sent to the agent. Typed accessors now reject any
  present-but-wrong-typed value per `session-input-v1.json`. Covered by `SessionArgumentTest`.
- **Shell background streams work.** `shell` read stdin on the `runBlocking` event loop that its own
  `&` jobs were dispatched from, so background streams never ran; the prompt also never flushed.
  Stdin now reads off the loop, jobs run on `Dispatchers.Default`, and shared state and output are
  serialized and is backed by the packaged PTY prompt/jobs/stop/Ctrl-C/EOF suite.
- **Native write failures surface.** `appendFileText`/`writeFileText` discarded the `fwrite` result,
  so on Native a full or read-only filesystem let the audit layer report success and dispatch the
  mutation anyway. Both now check `fwrite` and `fflush`.
- **Over-long session records are rejected, not split.** `fgets` stops at the buffer bound and
  reports nothing, so Native silently split an oversized record into two while the JVM raised a usage
  error. Both runtimes now reject it, consume through the record boundary, and let the session
  continue with the next record. `InputRecordJvmTest`/`InputRecordNativeTest` assert the same cases.
- **Session output state is race-free.** `outputBroken` is an `AtomicBoolean`; `sequence` remains a
  plain var because only the single writer coroutine touches it.
- Documentation repairs are listed under the reconciliation decisions below.

## Local verification

The final local run on 2026-08-14 completed in one Gradle invocation: core and CLI JVM tests,
packaged integration tests, matching macOS core/CLI Native tests, synchronized JVM↔Native handoff,
the pinned 0.11.0 live-agent suite, the JVM and all three Native archives, and `formatCheck`. It
finished successfully in 3m 8s; the live suite recorded 28 tests, zero skips, and zero failures.

Every built archive's embedded `SHA256SUMS` verifies after extraction. The JVM and macOS launchers
execute locally and report the expected version; Linux x64/ARM64 binaries cross-build on macOS, with
actual execution delegated to their matching CI runners. All workflow YAML parses after the action
and task updates.

These checks do prove the packaged CLI against the released simulator and the raw-client denied-write
case. They do not replace the first hosted Linux results, pre-release clean-install smoke on each
matching target, or any physical BLE connection-count/descriptor evidence.

The rebased upstream branch additionally passes focused Kotlin agent/protocol and
status/operator/raw-policy integration suites, plus all 175 Rust tests and clippy. Its aggregate
Gradle build compiled across targets but one LAN-dashboard test could not run because this host
resolved its local hostname to `127.0.0.1`; the other 95 client SDK JVM tests passed.

## Reconciliation decisions

- The upstream base is the released `v0.11.0` tag. Validate the CLI against a 0.11.0 agent; the
  earlier decision to hold `feat/cli-readiness` unmerged is superseded by that release.
- Preserve its explicit wildcard write-policy behavior.
- Avoid `.v1` capability suffixes unless a distinct capability is required to disambiguate two
  behaviors. Existing `agent.status` and `write.policy` names remain the starting point.
- Add only `lease.holder` for structured holder data an older client must not expect; slots remain
  the immediate legacy event and no query capability is introduced.
- `hex`, `base64`, and `raw` output stay reachable only through `--output`. `--hex`/`--base64`
  already name write payload sources, and one spelling cannot mean both an input encoding and an
  output mode. `docs/cli-reference.md` previously documented them as bare flags and illustrated
  envelope shapes (`characteristic-read`, `notification`, `value.decoded`) that were never emitted;
  its examples are now taken from the implementation.
- `docs/state-model.md` no longer describes the desktop grace-window work as outstanding: the JVM
  and Rust agents both expose it and transport grace defaults to 120 s. The phone agents remain the
  open half.
- The stale `AgentStatus`/`LeaseSummary`/`GlobalSlots` model is deleted — it described a contract
  the protocol's `AgentStatusDto` now supplies — along with the unused `scanFlow`/`observe` gateway
  methods.

## Remaining release gates

The automated coding gates are closed: frontend write behavior, process/PTY lifecycle, bounded
streams, strict schemas, critical local state, live simulator coverage, packaging, and matching
macOS cross-runtime locking all pass. No scripted test agent is required.

1. Push the current branch to the intended host and capture one green `verify.yml` run, including
   matching Linux x64/ARM64 core and CLI Native tests, lock handoff, and extracted-archive execution.
   Successful job logs and the run URL are the evidence; diagnostics upload only on failure.
2. Download the candidate archives from that exact run and perform the clean-install smoke on every
   matching target host, recording outer archive digests and extract-first manifest results.
3. Complete the physical desktop 2×2 matrix and hardware-only `descriptor read`, then prepare the
   redacted evidence record. Mobile and Raspberry Pi remain outside v0.1.
4. Create or designate the public repository, secret-scan the existing coherent history, decide
   whether the two internal planning documents are public, and configure branch protection,
   Dependabot, secret scanning, and private vulnerability reporting.
5. Freeze flags/schemas and the Skill, tag the already-qualified commit, let `release.yml` publish,
   then download and verify every published archive before announcing the release.
