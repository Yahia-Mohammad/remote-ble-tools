# Release plan — remoteble v0.1.0

The command surface and its upstream agent contracts are released in RemoteBLE **0.11.0**. The
remaining release work is **focused automated evidence, matching-host validation, and publication**
— see [`progress.md`](progress.md) for the current evidence.

## Agreed v0.1 execution plan

The release will close high-value behavior boundaries without reimplementing an agent or protocol
test server. The released JVM agent in `--simulate` mode remains the packaged-test peer. Faults
that agent cannot create deterministically stay covered at the existing transport/service boundary;
in particular, cancellation after write submission is already sufficient evidence for the
indeterminate-write mapping. A new scripted agent is explicitly out of scope.

1. [x] Freeze the session exit contract: a well-formed `session --jsonl` process exits 0; each
   response envelope reports the command's `exitCode`, including retryable agent-unreachable 9.
2. [x] Add a small packaged-CLI process/PTY harness, using the released simulator rather than a
   second agent implementation.
3. [x] Cover the essential JVM lifecycle boundary: shell prompt/jobs/stop/Ctrl-C/EOF, broken pipes,
   bounded slow consumers, and simulator-supported disconnect behavior.
4. [x] Validate every declared output-variant shape with table-driven schema goldens, plus
   representative packaged one-shot and session records.
5. [x] Close the critical local-state cases: permissions, retention, invalid identity,
   first-create races, multiprocess audit integrity, and live JVM-to-Native lock handoff.
6. [ ] Run those suites on their matching hosts in CI, retaining diagnostics only when a test fails.
7. [ ] Perform clean-install and physical desktop-agent validation, then capture release evidence.
8. [ ] Freeze the public surface, complete publishing checks, tag `v0.1.0`, and verify downloaded
   artifacts.

Dead SDK cleanup, provenance attestation, distribution packages, Intel-native macOS, mobile/Raspberry
Pi validation, and exhaustive timing/chaos testing are deliberately deferred until after v0.1.

---

## Phase 0 — Pre-flight (half a day)

Cheap corrections that would otherwise contaminate later evidence or the public history.

Four defects from the third review are fixed here and need no further work; they are listed so the
phase records what changed rather than only what is left:

- [x] **Release archives shipped non-executable launchers.** Gradle 9 normalizes archive entry
      permissions, so the staged exec bits never reached the ZIPs and a clean extraction could not
      run the CLI. The archive specs now set `0755` on `dist/bin/*`, and CI extracts and asserts
      `test -x` instead of listing entry names.
- [x] **Cancelled streams could not emit their terminal records.** `stream.stop` and slow-consumer
      handling emitted `command.error`/`stream.closed` with suspending sends from a cancelled
      coroutine. Teardown is now one `NonCancellable` section shared by scan and observe.
- [x] **A zero or negative `writeRateWindow` disabled the write limiter.** Validation checked only
      that the duration parsed; it now requires a positive, finite one, and both the service
      boundary and the ledger refuse a non-positive window.
- [x] **The write and policy documentation could not be followed.** `safety-model.md` used a
      `writes:` schema the loader rejects and described agent-side policy as pending upstream work;
      the write examples omitted the required `--write-type`; `agent-skill.md` used `--service` and
      `--characteristic` flags that do not exist. Every documented invocation and configuration file
      in `docs/`, `README.md`, and `skills/` was then checked against the built CLI.

Still open:

- [x] **Replace the placeholder SBOM.** The `:cli:cyclonedxDirectBom` task now generates the JVM
      release `sbom.json` from the resolved `jvmRuntimeClasspath`; it declares CycloneDX 1.6, an
      application component named `remoteble`, and 72 library components with hashes, licenses, and
      PURLs. Verified in the release archive.
- [x] **Extend checksums and SBOM to the native distributions.** Each native archive now stages its
      own `SHA256SUMS` and a per-target `sbom.json` generated from that target's resolved
      `*CompileKlibraries` (62 macOS components, incl. `-macosarm64` variants). `SHA256SUMS`
      covers every distributed regular file, including the SBOM, and verifies with `shasum -a 256 -c`.
- [x] **Single-source the version.** `remoteble.version` is declared once in `gradle.properties`.
      `build.gradle.kts`, the three places in `cli/build.gradle.kts`, the compiled `CLI_VERSION`, the
      JVM launcher script, and the workflow archive paths all read it (or glob it) rather than
      hard-coding `0.1.0-SNAPSHOT`. Verified by bumping the property and regenerating every artifact.
- [x] **Freeze the session exit contract.** `session --jsonl` exits 0 when its input records were
      processed, even when a record could not reach the agent; that record reports `exitCode: 9`
      inside its response envelope. This keeps a persistent machine session's transport health
      separate from per-command outcomes.
- [x] **Fail closed on an incompatible `ServerHello.version`.** The session now validates the
      selected server version through `selectProtocolVersion`; it clears capabilities, fails all
      pending work with `INCOMPATIBLE_PROTOCOL`, and refuses subsequent commands even if the peer
      leaves the socket open. `AgentSessionProtocolVersionTest` covers matching and mismatched
      hellos.
- [ ] Deferred after v0.1: remove the dead extracted-SDK surface (`JsonProtocolCodec`,
      `selectProtocolVersion`/`ProtocolVersionSelection`, `supportsCapability`,
      `RetryPolicies.untilElapsed`) and the CLI-only `Op.AgentSlots`/`ResultPayload.AgentSlots`
      compatibility decoders, which exist in this repository but not upstream.

**Exit:** `./gradlew clean check` green, and the release archive contains no file that overstates
what it describes.

---

## Agents: which one, where, and why

Two 0.11.0 agents are published, and they are **not interchangeable for this purpose**.

| | JVM agent fat JAR | `ghcr.io/yahia-mohammad/remoteble-agent-rs` |
|---|---|---|
| Source | `remoteble-agent-0.11.0-all.jar` on the `v0.11.0` GitHub Release | GHCR, tags `0.11.0`/`0.11`/`latest`, linux amd64+arm64, **anonymously pullable** |
| Radio-less mode | **Yes** — `--simulate` / `REMOTE_BLE_SIMULATE` | **No** |
| Runs on a CI runner | Yes, any OS with a JDK | No |
| Needs | JDK 17 | Linux host with BlueZ and `/run/dbus/system_bus_socket` mounted |
| Use it for | Phases 1–3 | Phase 5, Rust half of the 2×2 |

The Rust agent has no simulated backend: its `FakeBackend` lives inside `#[cfg(test)] mod tests`
(`agent-rs/src/transport/server.rs`) and is unreachable at runtime, and `main.rs` constructs
`BtleplugBackend` unconditionally. A GitHub-hosted runner has no Bluetooth adapter, and Docker
Desktop on macOS has no Bluetooth passthrough, so the container cannot serve automated validation on
either. That is not a defect — it is a real agent for real radios, and it removes the need to build
Rust with `libdbus` dev headers on the rig, which is exactly what Phase 5 wants.

The JVM simulator is documented upstream as being "for CI, demos, and reproducible client
integration tests". Its canonical `sim-hrm.json` profile covers scan, connect, discover, read,
write, and observe.

> **Constraint that moves work to Phase 5.** The simulator models exactly one capability beyond the
> v1 baseline — connected RSSI. **Descriptors, pairing, and connection parameters are unsupported**,
> so `remoteble descriptor read` cannot be validated in simulation and must be proven on hardware.

---

## Phase 1 — Complete the acceptance coverage (1–2 days) · **the gate**

First contact is done. `:integration-tests:liveAgentTest` runs the packaged fat JAR against the
**0.11.0 JVM agent in `--simulate` mode** and passes 28 tests locally; the same required task is
configured in CI. The wire shape, `agent.status` decode, capability negotiation, and both sides of
the write-policy boundary are proven rather than assumed. What the simulator cannot prove is the
hardware-only descriptor path and the physical desktop matrix.

Run the same agent locally when working on the gaps:

```sh
curl -fsSLO https://github.com/Yahia-Mohammad/remote-ble/releases/download/v0.11.0/remoteble-agent-0.11.0-all.jar
curl -fsSLO https://github.com/Yahia-Mohammad/remote-ble/releases/download/v0.11.0/remoteble-agent-0.11.0-all.jar.sha256
shasum -a 256 -c remoteble-agent-0.11.0-all.jar.sha256
REMOTE_BLE_TOKEN=dev-token \
REMOTE_BLE_POLICY_FILE=./policy.json \
  java -jar remoteble-agent-0.11.0-all.jar --simulate sim-hrm.json
```

Missing, in the order they are worth doing:

- [x] **Scenario 8 — simulated disconnect.** The profile now uses a dedicated peripheral with
      `connect.dropAfterMs`; an observe command receives notifications, then terminates with a
      typed `DISCONNECTED` error naming the affected handle.
- [x] **Scenario 10 — the full steps 4–7 workflow.** The live fixture's dedicated lease peripheral
      now models the heart-rate notification characteristic, and the scenario runs `connect` → 30 s
      pause → `inspect` → `read` → bounded `observe` → allowed `write` as separate invocations.
- [x] **Raw-protocol denied-write proof.** A minimal CBOR/WebSocket client now negotiates
      `write.policy`, connects as the denied principal, and receives `POLICY_DENIED` directly from
      the released agent without invoking this CLI's local policy service.
- [x] **Write frontend matrix across one-shot, `session`, and `shell`** (gate 1). Allowed,
      local-policy-denied, and rate-limited writes are proven through all three frontends.
      Indeterminate writes remain covered at the operation-service cancellation boundary; duplicating
      that proof with a bespoke timing agent would add another protocol implementation, not useful
      release evidence.
- [x] **Scenario 9 — the diagnostic report.** `remoteble report` returns chronological, redacted
      local audit records; the live scenario invokes it after a read and proves the command/result
      are present while the bearer remains absent.
- [ ] **Capture hosted evidence.** On the intended candidate commit, record the successful
      `acceptance` job URL and conclusion plus the pinned agent version/checksum. The workflow
      uploads its HTML report only on failure, so a successful run must not be documented as a
      retained report artifact; physical command output belongs in the Phase 5 redacted record.
- [ ] `descriptor read` is the one scenario the simulator cannot serve — Phase 5, on hardware.

**Exit:** all twelve MVP scenarios pass against a live agent, with output captured as evidence.
Twenty-eight passing tests is not that claim: they fully cover eleven of the twelve scenarios (1–12
except descriptor read, which needs hardware).

---

## Phase 2 — Focused automated coverage

- [x] **Packaged process and PTY harness.** It provides only the controls the CLI tests need: interactive
      stdin, awaited output, paused/closed stdout, EOF, and SIGINT. It drives the built JVM CLI and
      released simulated agent; it is not an alternative protocol client.
- [x] **Stream lifecycle** (gate 2): the packaged JVM suite exercises foreground/background jobs,
      `jobs`, `stop STREAM_ID`, terminal Ctrl-C, EOF, a real broken pipe, and recovery after the
      simulator drops a stream. Matching-host Native process smoke remains part of Phase 3 CI.
- [x] **Bounded slow consumer.** The production 256-record output queue is deterministically filled
      in a shared JVM/Native test; it rejects a new event while preserving terminal output in order.
- [x] **Schemas and UX** (gate 3): NetworkNT's Draft 2020-12 validator checks every declared
      one-shot discriminator and every session record union (including both stream-event forms).
      The packaged JVM CLI additionally validates real one-shot, stream, and session records against
      the schemas it ships.
- [x] **Logging and state** (gate 4): permissions, retention, debug fallback, multiprocess audit
      integrity, curated redaction cases, identity first-create races, and invalid persisted identity.
- [x] **JVM↔Native `.state.lock` handoff** (gate 9) on a matching macOS host, using actual built
      executables rather than a mocked lock implementation.

**Exit:** gates 2, 3, 4, and 9 closed.

---

## Phase 3 — CI on GitHub Actions (1 day)

`.github/workflows/verify.yml` covers dependency locks, JVM tests, released-agent acceptance, and
the three native targets. Each native host now runs matching core and CLI Native tests, its
JVM↔Native lock handoff, and the freshly extracted native archive; the package job executes the
freshly extracted JVM archive. The configuration is complete, but its first GitHub-hosted run is
still release evidence to capture.

### Harden `verify.yml`

- [ ] Change the branch trigger from `codex/**` to whatever the public repository actually uses.
- [x] **Move the macOS job off `macos-14` — done.** Its deprecation began on 6 July 2026, with
      brownouts during the window and full removal on 2 November 2026; the job now runs on
      `macos-15`. Keep this in view: every macOS label is on a rolling two-year clock.
- [x] **Live-agent job — done.** `verify.yml` now has an `acceptance` job that runs
      `:integration-tests:fetchAgent` (pinned 0.11.0, checksum-verified) then
      `:integration-tests:liveAgentTest -Premoteble.agent.required=true`. The suite starts one
      radio-less agent for its lifetime, so no container, privilege, or adapter is involved. The
      `required` flag exists because a misconfigured path would otherwise skip every scenario and
      report green.
- [x] The JVM job runs deterministic process/PTY/schema tests, and the acceptance job runs the
      released-agent lifecycle suite; diagnostics upload only on test failure.
- [x] Pin actions to immutable commit SHAs, set `permissions: contents: read`, and isolate release
      write permission to the final publishing job.
- [x] Add a concurrency group so pushes cancel superseded verification runs.
- [x] Cache the Gradle and Kotlin/Native toolchains with `gradle/actions/setup-gradle`.

### Add `release.yml`

- [x] `release.yml` triggers on `v*` tags and builds the JVM archive plus all three native archives
      on matching hosts (`macos-15`, `ubuntu-latest`, `ubuntu-24.04-arm`), verifies archive
      checksums, then runs each freshly extracted matching-host launcher before attaching the four
      artifacts to a GitHub Release.
- [x] The release builds set `-Premoteble.version` from the tag, so archive names, compiled
      `--version` output, generated SBOMs, and checksums all describe the release rather than the
      development snapshot. GitHub generates the release notes until a curated `CHANGELOG.md` lands.
- [ ] Consider build provenance attestation (`actions/attest-build-provenance`) — cheap, and it is
      the credible version of what `sbom.json` currently only gestures at.

### Add supporting workflows

- [x] Add read-only pull-request secret scanning (full-history Gitleaks) and GitHub dependency
      review, pinned to immutable action commits. The first hosted run remains release evidence.
- [x] Add a weekly scheduled run of the full verification matrix, so toolchain drift surfaces
      between releases; Dependabot also opens grouped weekly updates for GitHub Actions and Gradle
      dependencies.

**Exit:** a tag produces a complete, checksummed release without manual publishing steps.

---

## Phase 4 — Public repository with clean history (half a day)

The project now has a coherent phased history: implementation, tests, packaging, documentation,
and the follow-up release-coverage commits are separately reviewable. No history rewrite is needed
before publication. Hosted repository settings and a final history secret scan remain external
release gates.

### Prepare

- [x] Keep the small, reviewable implementation/test/packaging/documentation sequence rather than
      collapsing it into one initial commit. The coverage follow-ups remain separate evidence.
      1. `build: bootstrap the Kotlin Multiplatform project`
      2. `feat: add the embedded RemoteBLE protocol client`
      3. `feat: add the core operation, policy, and audit layer`
      4. `feat: add the one-shot CLI command surface`
      5. `feat: add the persistent JSONL session and human shell`
      6. `test: add unit, native, and packaged integration coverage`
      7. `build: add release packaging and CI`
      8. `docs: add the design, reference, and safety documentation`
- [x] Confirm `.gitignore` covers `.claude/`, `build/`, `.gradle/`, and `.integration/`.
- [x] Re-check cross-repository links. There are no local filesystem links; upstream source links
      target `github.com/Yahia-Mohammad/remote-ble` at `v0.11.0`.
- [x] Verify `LICENSE` and `NOTICE` are accurate. `NOTICE` credits derivation from Remote BLE
      Transport **0.11.0**; keep it in step whenever the protocol slice is re-synced.
- [ ] Decide what `docs/` is public. `implementation-plan.md` and `progress.md` are internal
      working documents that reference branch names and upstream file paths. Either publish them
      knowingly as engineering history, or keep `docs/` to the reference material
      (`cli-reference`, `session-protocol`, `safety-model`, `state-model`, `concept`,
      `slim-client`, `agent-skill`, `profiles`, `logging-audit`) and retain the rest privately.

### Publish

- [ ] Create or designate the public repository and push this existing coherent history. Do not copy
      the tree into a fresh history or reconstruct the reviewed commit sequence.
- [ ] Run a secret scan (`gitleaks detect`) over the new history before the first push, not after.
- [ ] Push, enable branch protection on `main` requiring `verify.yml`, and enable secret scanning
      and Dependabot.
- [x] Add `CONTRIBUTING.md`, `SECURITY.md`, and a `CHANGELOG.md` seeded with pending `0.1.0`
      release notes. Private vulnerability reporting still needs enabling in the hosted repository.
- [ ] Keep this repository as the private working checkout, or archive it once the public one is
      authoritative — but do not maintain both.

**Exit:** a public repository whose first commit sequence a stranger can read, with green CI.

---

## Phase 5 — Hardware acceptance (scheduling-bound)

- [ ] The desktop 2×2 matrix (gate 7): macOS CLI and Linux CLI against both the JVM/macOS agent
      and the Rust agent, with evidence attached. **This is the release-blocking hardware gate.**
      Follow the fixed command/evidence record in
      [`hardware-validation.md`](hardware-validation.md); it is intentionally a manual protocol,
      not a new scripted agent.
      For the Rust half, use the published container rather than building from source — it is
      anonymously pullable and multi-arch:

      ```sh
      docker run --rm -p 8080:8080 \
        -e REMOTE_BLE_TOKEN=... -e REMOTE_BLE_POLICY_FILE=/etc/remoteble/policy.json \
        -v ./policy.json:/etc/remoteble/policy.json:ro \
        -v /run/dbus/system_bus_socket:/run/dbus/system_bus_socket \
        ghcr.io/yahia-mohammad/remoteble-agent-rs:0.11.0
      ```

      Do not add `--privileged`, host networking, or HCI mounts; upstream documents that explicitly.
      Note the image's own validation scope: one amd64 Linux host, with AppArmor and arm64
      unvalidated.
- [ ] Clean-install smoke on each matching target host, using only candidate artifacts downloaded
      from one successful `verify.yml` run for the exact intended tag commit (gate 5). Record each
      archive's outer SHA-256, then extract it and verify the embedded `SHA256SUMS`; the manifest is
      inside the ZIP and cannot be checked before extraction.
- [ ] Validate `descriptor read` here — it is the only command with no simulator coverage.
- [ ] Mobile and Raspberry Pi pairings are explicitly **out of scope for v0.1**. The one remaining
      upstream gap — no operator control of the grace windows on Android and iOS — sits behind that
      boundary and does not block this release.

**Exit:** gates 5 and 7 closed, with the evidence record ready to attach after publication.

---

## Phase 6 — Ship

- [ ] Freeze flags and output schemas. Only then finalise `skills/remoteble`, which is documented as
      depending on a frozen surface.
- [ ] Tag the already hardware-qualified commit as `v0.1.0` and let `release.yml` publish.
- [ ] Download every archive from the GitHub Release into a clean directory, record its outer
      SHA-256, extract it, verify its embedded `SHA256SUMS`, and repeat the version/help/config smoke
      on each matching host. Confirm the tag resolves to the candidate commit recorded in Phase 5.
- [ ] Announce with the honest scope: desktop agents validated, mobile untested.

---

## Phase 7 — Post-v0.1 distribution channels (1–2 days)

Both are viable and cheap, and the artifacts are already in good shape for them. The findings below
come from inspecting the built binaries, not from assumption.

### What the binaries make possible

| Fact | Consequence |
|---|---|
| macOS binary is **ad-hoc (linker) signed**, `Mach-O arm64` | Executes on Apple Silicon without a Developer ID or notarization, as long as it carries no quarantine attribute — which Homebrew *formula* downloads do not set |
| macOS binary links **only system dylibs** (`libSystem`, `libc++`, `Foundation`, …) | Nothing to declare or bundle |
| Linux binary needs only glibc-family libraries: `libc`, `libm`, `libpthread`, `libdl`, `librt`, `libutil`, `libresolv`, `libcrypt`, `libgcc_s` | No OpenSSL, curl, or D-Bus dependency — unlike the agent |
| Linux **glibc symbol floor is 2.14** (2011) | Runs on every currently supported distro: Debian 11+, Ubuntu 20.04+, RHEL 8+, Fedora |
| ~8.4 MB (macOS) / ~9.0 MB (Linux), self-contained | No JRE dependency for the native builds |

### Prerequisites (do these first — they are small and both channels want them)

- [x] **Write a man page** (`remoteble.1`). It now ships in `dist/man/` in the JVM archive and all
      native archive definitions, covering the command surface, configuration, environment, and
      exit contract expected by package linters.
- [x] **Ship bash completion.** A completion for `remoteble` and `rble` now ships beside the zsh
      and fish assets; archive verification asserts both the completion and manual page exist.

### Intel macOS: paused, on purpose

There is **no native Intel macOS build**, and adding one is deferred until someone asks. It was
implemented and then reverted once the trade-off was clear:

- Kotlin/Native **deprecates `macosX64`** — the compiler warns it "will be removed in a future
  release" (`kotl.in/native-targets-tiers`), tracking Apple's own Intel wind-down.
- GitHub's `macos-13` Intel runners, the only place its tests could actually execute, were
  **retired on 4 December 2025**; `macos-15-intel` and `macos-26-intel` are the remaining Intel
  labels. Kotlin skips a native test task when the host cannot run the target, so an arm64 runner
  would build the binary without ever testing it.
- The produced x64 binary was **unsigned**, where the arm64 one is ad-hoc linker-signed. Harmless
  for execution, but a loose end the moment notarization matters.

All three point the same way: a native Intel binary would arrive already on a countdown. **Intel
Macs are served by the JVM fat JAR**, which needs a JDK but no separate target — so coverage is
complete either way, and the formula carries the difference. Re-adding it is roughly an hour: one
line per module, an archive spec, and a CI job on a matching host. It was verified to work when
tried (26 Native unit tests passed on the x64 binary), so this is a maintenance decision, not a
technical unknown.

### macOS — Homebrew

Use a **custom tap** (`Yahia-Mohammad/homebrew-tap` — it does not exist yet), not `homebrew-core`.
Core imposes notability thresholds a new project will not meet and strongly prefers formulae that
build from source; a Kotlin/Native source build there is impractical. A tap has no review gate, is
fully automatable, and installs in one command:

```sh
brew install Yahia-Mohammad/tap/remoteble
```

- [ ] Create the tap repository with `Formula/remoteble.rb`. With no Intel build, the formula has two
      honest shapes — pick one deliberately:
      - **Apple Silicon only**: `depends_on arch: :arm64`, install the native binary. Simplest, and it
        turns Intel users away with a clear message rather than a broken install.
      - **Native on arm64, JVM on Intel**: `on_arm` installs the binary; `on_intel` installs the fat
        JAR with `depends_on "openjdk"` and a small wrapper script. Full coverage, one more moving
        part, and the JVM path is already built and tested every CI run.
      Either way, install `rble`, the three completions, and the man page.
- [ ] Automate the bump from `release.yml`: after artifacts are attached, compute the checksums and
      push the updated formula to the tap. `brew bump-formula-pr` handles the same job for core-style
      repositories and can be reused here.
- [ ] Add `brew test-bot`-style CI in the tap, or at minimum a job that installs the formula on a
      clean runner and runs `remoteble --version`.

**The agent can live in the same tap**, and this is worth doing — `remoteble-agent-rs` is a plain
binary, and the JVM agent is a fat JAR needing only `depends_on "openjdk@17"` and a wrapper script.
Note the ownership boundary: those artifacts are released from `remote-ble`, so either that
repository's release workflow bumps the shared tap, or the tap pulls from both. Decide before
writing the automation; a tap fed by two repositories needs one owner.

### Linux — `.deb` and `.rpm`

Straightforward, because the CLI is a single self-contained binary with no service, no config
daemon, and no privileged access.

- [ ] Use **`nfpm`**: one YAML produces `.deb`, `.rpm`, and `.apk` for amd64 and arm64, it is a
      single Go binary, and it needs no Debian or RPM tooling on the runner. (`fpm` and native
      `dpkg-deb`/`rpmbuild` also work; `jpackage` is the wrong tool here — it bundles a JRE.)
- [ ] Standard layout: binary to `/usr/bin/remoteble` with the `rble` alias, completions to
      `/usr/share/bash-completion/completions/`, `/usr/share/zsh/site-functions/`, and
      `/usr/share/fish/vendor_completions.d/`, `LICENSE`/`NOTICE` to `/usr/share/doc/remoteble/`,
      and the man page to `/usr/share/man/man1/`.
- [ ] **Declare `libcrypt.so.1` explicitly.** This is the one real gotcha: on Fedora and RHEL 9 it
      ships in `libxcrypt-compat`, which is *not* installed by default, so the binary would fail to
      start with no useful message. `rpmbuild`'s automatic dependency generator catches this; `nfpm`
      does not, so it has to be declared by hand.
- [ ] Attach the packages to a follow-up GitHub Release. That alone gives `dpkg -i` / `rpm -i`
      installs without turning hosted package repositories into a maintenance commitment.

**Deliberately deferred:** hosted apt/yum repositories (they need GPG signing and hosting — GitHub
Pages can do it, but it is a maintenance commitment), and inclusion in official Debian or Fedora
archives (sponsorship and packaging policy, a far larger lift than the packages themselves).

**Packaging the agent for Linux is a bigger job than the CLI** and belongs upstream: `agent-rs`
needs BlueZ and D-Bus at runtime, so its package must declare `bluez`, ship a hardened systemd unit,
and define a service user and config location. The published container already covers the common
deployment, so this is the lowest-priority item here.

### Recommendation

After the initial v0.1 ZIP release, do the **Homebrew tap** first — it is the largest usability win
for the primary audience and costs about half a day. Add **`.deb`/`.rpm` release assets** in the same
follow-up; `nfpm` makes them near-free once the man page and bash completion exist. Defer repository
hosting, official distro inclusion, and agent packaging until there is demand.

---

## Critical path

```
Phase 0 ──► Phase 1 ──► Phase 2 ──► Phase 3 ──► Phase 4 ──► Phase 6
                 └──────────────────────────────► Phase 5 ──┘
                                                              └──► Phase 7 (post-v0.1)
```

Phase 7 depends on a tagged release but is not a v0.1 release gate.

Phase 1 gates everything. Phase 5 can be scheduled in parallel from the end of Phase 1, since it
needs hardware time rather than more code. Phases 3 and 4 can overlap: build the workflows against
the private repository, then carry them over.

The remaining hands-on work is hosted-repository configuration, one complete hosted run, and the
release operation; calendar time is dominated by scheduling the physical matrix. Phase 7 is a
separate 1–2 day follow-up.

## Biggest risks

1. **Physical behavior differs from simulation.** Twenty-eight live acceptance tests exchange frames
   with the released 0.11.0 simulator, but descriptor access, connection counts, and the JVM/Rust
   desktop matrix still need hardware evidence.
2. **A matching-host or hosted-only failure appears.** Linux executables cross-build locally but must
   execute on their CI architectures; the first complete hosted run is therefore a real gate.
3. **Publishing before the surface is frozen.** Once the Skill and JSONL schemas are public, callers
   encode them; freeze them before tagging rather than repairing the contract after release.
