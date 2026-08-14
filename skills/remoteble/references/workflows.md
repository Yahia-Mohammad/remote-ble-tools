# Workflows

Procedures, not flag lists. Each one exists because doing it in a different order produces worse
evidence or risks the hardware.

## Contents

- [Bring-up of an unknown peripheral](#bring-up-of-an-unknown-peripheral)
- [Authorized write, end to end](#authorized-write-end-to-end)
- [Hardware-in-the-loop test authoring](#hardware-in-the-loop-test-authoring)
- [BLE and serial correlation](#ble-and-serial-correlation)
- [Diagnostic report](#diagnostic-report)
- [Driving many operations from one session](#driving-many-operations-from-one-session)

## Bring-up of an unknown peripheral

The goal is a document a human can check, not a claim that the device "works."

```bash
remoteble agent status
remoteble --jsonl scan --duration 10s              # bounded; jsonl gives name, rssi, services
remoteble --json inspect sim-hrm-1                 # the GATT tree, with properties
remoteble --json read sim-hrm-1 180f 2a19          # every readable characteristic, one at a time
```

1. **Scan with a bounded window.** Ten seconds is usually enough; a device that advertises slowly
   may need more, which is itself a finding worth recording.
2. **Select unambiguously.** If several peripherals match, list them with their handles and RSSI and
   ask which one. Picking the strongest signal is a guess that will eventually be wrong, and on
   shared hardware it may be someone else's device.
3. **Inspect before reading.** `inspect --json` gives each characteristic's `properties` array.
   Read only what advertises `read`; subscribing to something without `notify` or `indicate` will
   fail and tells you nothing.
4. **Read every readable characteristic** and tabulate: UUID, SIG name if known, raw hex, length.
5. **Propose a protocol hypothesis** — what you think the bytes mean — clearly labelled as a
   hypothesis, with the raw bytes beside it.
6. **Confirm cheaply.** Where a value should change (a counter, a battery level, a measurement),
   read twice and compare, or subscribe briefly. A read-back is free; a write is not.

Stop there. Proposing a write is a separate step that needs a human.

## Authorized write, end to end

A write needs three independent things to line up, and it is worth checking them in this order
because each is cheaper than the next to diagnose:

1. **The characteristic accepts it.** `inspect --json` shows `write` or `write-without-response` in
   its `properties`.
2. **Local advisory policy permits it.** The `policy.writeRules` list in the config file must name
   this exact endpoint, device, service, characteristic, size, and write type. This is a guardrail
   against mistakes, not a security control.
3. **The agent enforces policy and permits it.** `agent status` must report
   `"writePolicyEnforced":true`; the CLI refuses to dispatch otherwise. The agent then checks its
   own per-principal allowlist, which is the real control.

```bash
remoteble --json write sim-hrm-1 180d 2a39 --hex 01 --write-type with-response
# {"schemaVersion":1,"type":"write","data":{"handle":"sim-hrm-1","length":1,"withResponse":true}}
```

Payloads are always explicitly encoded: exactly one of `--hex`, `--base64`, `--text`, or
`--stdin hex|base64|text`. There is no "guess the encoding" mode, because guessing wrong sends
different bytes to real hardware.

**Choosing a write type.** `with-response` asks the peripheral to acknowledge, so a failure is
reported; use it by default. `without-response` is fire-and-forget — faster, and appropriate for
high-rate streams where a dropped packet does not matter — but a failure is silent, so never use it
for anything that actuates.

**If the write exits 8**, see the exit-code table in the main skill file. Read back, or escalate.

## Hardware-in-the-loop test authoring

The point of the simulator is that a procedure can be made deterministic before it touches hardware.

1. Author the procedure against an agent running in `--simulate` mode. It is free, reproducible, and
   cannot damage anything.
2. Run the identical commands against real hardware.
3. **Divergence between the two is the finding.** Record both transcripts; a procedure that passes
   in simulation and fails on hardware has told you something specific about the device.

The simulator does not model descriptors, pairing, or connection parameters, so those surfaces have
to be proven on hardware regardless.

## BLE and serial correlation

An embedded device usually exposes two surfaces: the product surface over BLE and the debug surface
over serial. Watching both at once is the difference between observing a symptom and locating a
cause — and it is the workflow a CLI makes natural and a tool API does not.

```bash
remoteble --jsonl observe dev_42 180d 2a37 --count 50 --timeout 60s > ble.jsonl &

picocom -b 115200 /dev/ttyUSB0 | ts '%Y-%m-%dT%H:%M:%.S' > serial.log &

remoteble write dev_42 180d 2a39 --hex 01 --write-type with-response
```

Both streams carry timestamps, so merging them is ordinary text processing: sort the two files by
timestamp and read the interleaving. Start the observer *before* the write — a notification emitted
during subscription setup is lost, and the causal link is the whole point.

## Diagnostic report

Written for someone who does not have the device in front of them. Collect, in this order:

1. `remoteble --version` and `remoteble agent status --json` — versions and agent state.
2. `remoteble agent capabilities` — what this agent supports, so a reader can tell "unsupported"
   from "broken."
3. `remoteble --json inspect HANDLE` — the GATT tree.
4. The relevant reads, with raw hex preserved.
5. **The exact commands run, verbatim**, including flags.
6. Exit codes for anything that failed, and the error envelope from stderr.

The test of a good report is whether a colleague can replay it. Paraphrased commands fail that test.

## Driving many operations from one session

Use `session --jsonl` when you need concurrency, streams alongside other commands, or many
operations without repeated startup. Read
[session-protocol.md](session-protocol.md) first — the two things that catch people out are that
**results arrive out of order** (correlate by `id`, never by position) and that **`session.close` is
answered immediately** while in-flight work continues to completion.
