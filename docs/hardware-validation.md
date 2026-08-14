# v0.1 hardware validation record

This is the release-blocking manual validation protocol. It deliberately uses CLI release-candidate
archives and released desktop agents; it does not introduce a second protocol client, simulated
peripheral, or hardware test framework. Qualify the exact commit intended for `v0.1.0`, then attach
the completed record to that release after publication.

## Preconditions

- Before tagging, download each CLI candidate archive from one successful `verify.yml` run for the
  exact commit intended for release. Record the workflow-run URL, commit SHA, archive filename,
  archive SHA-256, and `remoteble --version` output. Do not rebuild locally for this gate.
- Use one safe, known peripheral whose GATT service, characteristic, and descriptor UUIDs are
  recorded below. Its write characteristic must be explicitly allowlisted and harmless.
- Give each agent its own nearby physical peripheral or otherwise ensure that the agent host has
  the radio path to the test device. Do not use host networking, privileged containers, or HCI
  mounts for the Rust-agent container.
- Use two distinct client identities for the contention check. Never include bearer or operator
  tokens, write payloads, or raw audit files in the record.

## Pre-release candidate smoke (release blocking)

Do this before tagging, once on every matching target host (macOS arm64, Linux x64, and Linux arm64
when it is a release target). Start from an empty directory and only the candidate archive
downloaded from the recorded workflow run. `SHA256SUMS` is inside the ZIP, so first record the
outer archive digest, then extract and verify the embedded per-file manifest:

```sh
# Replace this example with the single candidate filename downloaded for this host.
archive=remoteble-macos-arm64-0.1.0-SNAPSHOT.zip
shasum -a 256 "$archive"
unzip -q "$archive" -d remoteble-candidate
( cd remoteble-candidate && shasum -a 256 -c SHA256SUMS )
./remoteble-candidate/dist/bin/remoteble --version
./remoteble-candidate/dist/bin/remoteble --help
./remoteble-candidate/dist/bin/remoteble config validate
```

Record each command's exit code and stdout/stderr. The GitHub workflows run the equivalent archive
smokes automatically; this confirms the retained candidate behaves the same way on the actual host.

## Post-publication download verification

After the validated commit is tagged and `release.yml` publishes `v0.1.0`, download every published
archive from the GitHub Release into a new empty directory. Record the release URL, tag, resolved
commit SHA, filename, and outer archive SHA-256. Repeat the extract-first manifest check and the
version/help/config smoke above, using a fresh extraction directory for each archive.

This step verifies publication and download integrity; it is not a reason to postpone the physical
matrix until after the release exists. The pre-release matrix remains applicable only when the tag
resolves to the recorded candidate commit. A commit mismatch or any failed downloaded-archive smoke
invalidates the release and requires correction before it is announced.

## Desktop-agent matrix

Perform every row below with a real peripheral. The JVM agent runs on macOS with its supported
Bluetooth launch method; the Rust agent runs on Linux with BlueZ and the documented container
invocation in [`release-plan.md`](release-plan.md#phase-5--hardware-acceptance-scheduling-bound).
Clients may run remotely over the network, but the endpoint must be recorded without credentials.

| Candidate client archive and host | Agent and radio host | Result |
|---|---|---|
| macOS arm64 Native CLI | JVM desktop agent on macOS | [ ] |
| Linux x64 Native CLI | JVM desktop agent on macOS | [ ] |
| macOS arm64 Native CLI | Rust desktop agent on Linux | [ ] |
| Linux x64 Native CLI | Rust desktop agent on Linux | [ ] |

For every cell, record the following as newline-delimited JSON or redacted terminal output. Use the
same safe peripheral and UUIDs where the radio layout permits; if a different device is necessary,
record the model and GATT shape instead of its address.

1. `agent status`, `agent capabilities`, and a bounded `scan` discover the expected peripheral.
2. `inspect`, `read`, and **`descriptor read`** succeed. Preserve the command, exit code, and
   structured output for the descriptor operation; it has no simulator coverage.
3. A bounded `observe` receives the expected notifications and closes cleanly.
4. An allowlisted, harmless `write` succeeds; a non-allowlisted write is denied by the agent.
5. In separate CLI invocations, pause for the configured grace interval, then `inspect`, `read`,
   `observe`, and `write`. Record the observed connection count and whether the same lease resumed.
6. While identity A holds the peripheral, identity B receives a lease-denied response that names
   the holder rather than timing out. Release the lease and verify B can proceed.
7. `report --json` contains the operation history but no bearer, operator credential, or write
   payload. Attach this redacted output rather than the underlying audit file.

## Evidence to attach

For each matrix cell, include the following small record in the release notes or a linked issue:

```text
Date / operator:
Candidate workflow run / commit SHA:
Candidate CLI archive / outer SHA-256 / --version:
Client OS and architecture:
Agent implementation / version / host OS:
Peripheral model / firmware / service-characteristic-descriptor UUIDs:
Endpoint (redacted):
Scenario results 1–7, command exit codes, and redacted JSON:
Grace interval and observed physical connection count:
Failures or deviations:
Published release URL / tag / commit SHA:
Published archive / outer SHA-256 / embedded-manifest result / smoke result:
```

Any failed command, unexpected physical reconnection, missing descriptor read, or credential/payload
leak blocks the release until it is explained and corrected. Mobile and Raspberry Pi pairings remain
outside v0.1.
