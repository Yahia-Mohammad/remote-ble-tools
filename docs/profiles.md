# Semantic profiles and the simulation loop

**Deferred to v1.1 at the earliest.** Recorded now because it changes what `core` should look like,
not because it should be built early.

## The layer

Raw GATT is a poor interface for a model. Composing UUIDs and byte payloads for every step is
error-prone and burns context:

```text
write 0x01 to characteristic fff2, wait, subscribe to fff3, decode bytes 2-3 as uint16 LE
```

A device profile collapses that into intent:

```text
remoteble thermometer start-measurement
remoteble thermometer collect-samples --count 10
```

This is where most of the long-term usefulness lives. It is also where
[es617's server](https://github.com/es617/ble-mcp-server) already operates, with protocol-spec
files and generated device plugins. **Do not compete there on their terms, and do not delay the
CLI to build it.**

## The version worth building

The differentiated version ties profiles to RemoteBLE's simulation, so **one artifact defines both
the simulated peripheral and the semantic commands exposed for it.**

```text
                  ┌─────────────────────┐
                  │   device profile    │
                  └──────────┬──────────┘
              ┌──────────────┴──────────────┐
              ▼                             ▼
   simulated peripheral            semantic CLI commands
   (agent --simulate)              (thermometer *)
              │                             │
              └──────────────┬──────────────┘
                             ▼
                 CI evaluation, no hardware
                             │
                   same commands, real device
```

What that buys, and nothing else in the landscape has any of it:

- **Evaluation in CI.** Semantic commands can be exercised against a declared peripheral with no
  radio, on a hosted runner. Regressions in Skill wording, argument shapes, and agent success rate
  become measurable rather than anecdotal.
- **Reproducible bug reports.** "The agent mishandles this device" ships as a profile, not as a
  device in the post.
- **Safety fixtures.** A profile can advertise a hostile name or return a malformed payload, making
  the injection and bounds tests in [`safety-model.md`](safety-model.md) executable. This is what
  makes acceptance scenario 11 in [`mvp-scope.md`](mvp-scope.md#acceptance-scenarios) possible.
- **Sim/real parity is checkable.** The same semantic command against both backends should produce
  the same shape; divergence is a bug in one of them.

## Integration constraint

RemoteBLE's simulation profile is `schemaVersion: 1` and its parser **rejects unknown fields**
(`SimulationProfile.kt`). A `semantics` block cannot simply be added to an existing profile without
an upstream schema change.

**Option A — sidecar (recommended).** A separate file here referencing a stock simulation profile:

```json
{
  "schemaVersion": 1,
  "simulationProfile": "sim-hrm.json",
  "device": "thermometer",
  "commands": [
    {
      "name": "start-measurement",
      "description": "Begin a measurement run.",
      "idempotent": false,
      "steps": [{ "write": { "characteristic": "…fff2", "value": "01" } }]
    }
  ]
}
```

No upstream change, independent iteration, and the simulation profile stays a pure GATT-world
description — arguably the correct layering anyway, since the simulator should not know about CLI
command names.

**Option B — upstream `schemaVersion: 2`.** One artifact, tighter coupling, requires coordinating a
schema bump in RemoteBLE and pushes tooling concerns into the agent. Worth it only if the
two-file split proves painful.

Start with A; record B as the fallback.

## Constraints when it is built

- **Profiles are operator-supplied, not agent-supplied.** A model that can define its own semantic
  commands can define one that writes anywhere, routing around the allowlist. Profile files are
  configuration, and they belong under the same trust assumptions as the policy block.
- **Every profile ships with its simulation counterpart.** A profile with no simulated peripheral
  cannot be tested, which forfeits the entire reason for building this layer here rather than
  contributing it to a more mature project.
- **Semantic commands never replace raw access.** `read` and `write` remain available. A profile is
  a convenience over the GATT surface, never a wrapper that hides what was actually sent.

## Why this is deferred

The raw CLI is what proves the remote-first thesis. A semantic layer over an unproven transport
tests nothing, and the temptation to start here is strong precisely because it is the interesting
part. Build it after the acceptance scenarios in [`mvp-scope.md`](mvp-scope.md) pass on hardware.
