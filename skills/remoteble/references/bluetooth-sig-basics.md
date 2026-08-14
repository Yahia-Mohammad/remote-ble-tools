# Bluetooth GATT basics

Enough BLE to work safely and to read what `inspect` returns. This is background for interpreting a
device, not a substitute for its datasheet.

## The shape of a peripheral

A peripheral advertises, then accepts a connection, then exposes a **GATT table**: services, each
holding characteristics, each optionally holding descriptors.

- **Service** — a group of related characteristics, identified by UUID. `180d` is Heart Rate.
- **Characteristic** — a single value you can read, write, or subscribe to. `2a37` is Heart Rate
  Measurement.
- **Descriptor** — metadata attached to a characteristic, such as its subscription setting or a
  human-readable description.

`remoteble inspect HANDLE --json` returns exactly this structure.

## UUID forms

The same UUID has three spellings, and the CLI accepts all of them:

| Form | Example |
|---|---|
| 16-bit | `180d` |
| 32-bit | `0000180d` |
| 128-bit | `0000180d-0000-1000-8000-00805f9b34fb` |

Short forms expand into the Bluetooth base UUID `xxxxxxxx-0000-1000-8000-00805f9b34fb`. Output
always uses the full 128-bit form, so a UUID you passed as `180d` comes back long — the same value,
not a different one.

A UUID **outside** that base range is vendor-defined. It will have no SIG name, and its meaning
comes only from the manufacturer's documentation. Do not guess.

## Properties decide what is possible

`inspect --json` gives each characteristic a `properties` array. Check it before attempting an
operation — this is the cheapest possible way to avoid a failed write.

| Property | Meaning |
|---|---|
| `read` | Value can be read |
| `write` | Write with acknowledgement (`--write-type with-response`) |
| `write-without-response` | Fire-and-forget write (`--write-type without-response`) |
| `notify` | Pushes updates; no acknowledgement |
| `indicate` | Pushes updates and expects acknowledgement — slower, more reliable |
| `broadcast` | Value may appear in advertisements |
| `authenticated-signed-writes` | Requires a signed write |
| `extended` | Further properties live in a descriptor |

`observe` covers both `notify` and `indicate`; the CLI does not make you choose between them.

## SIG names the CLI recognises

These get a `name` field in output. Everything else is unnamed — which means unrecognised, not
meaningless.

| UUID | Name |
|---|---|
| `1800` | Generic Access |
| `1801` | Generic Attribute |
| `180d` | Heart Rate |
| `180f` | Battery Service |
| `2a19` | Battery Level |
| `2a37` | Heart Rate Measurement |
| `2a38` | Body Sensor Location |
| `2a39` | Heart Rate Control Point |

## Reading values

Values are byte arrays. The CLI never guesses at their meaning — it returns hex, Base64, and length,
and leaves interpretation to you.

Two things reliably catch people out:

- **Endianness.** Most BLE multi-byte integers are little-endian, so `003c` as a 16-bit value is
  `0x3c00` = 15360, not 60. Which bytes form which field comes from the specification for that
  characteristic.
- **Flags bytes.** Many SIG characteristics begin with a flags byte that determines the layout of
  everything after it. Heart Rate Measurement is the classic case: bit 0 of the first byte says
  whether the measurement is 8-bit or 16-bit. Parsing at a fixed offset without reading the flags
  produces plausible, wrong numbers.

Battery Level is the easy case: one byte, a percentage. `{"hex":"64"}` is 100%.

## Payload size

An attribute write is bounded by the negotiated MTU, typically leaving about 20 bytes usable on a
default connection and more after MTU negotiation. Local policy also caps payload size. A write
that exceeds either is rejected before it reaches the radio — check the datasheet for what the
characteristic actually expects rather than padding to fit.

## Connections, leases, and grace

A BLE connection is exclusive: while one central is connected to a peripheral, another cannot be.
RemoteBLE models this as a **lease**, held by your client identity and kept warm across separate
CLI invocations for a grace period (`leases[].remainingGraceMs` in `agent status`). That is why a
second `read` moments after the first is fast, and why forgetting `disconnect` blocks colleagues.

Adapters also have a fixed number of connection slots — `agent slots` reports free and total. A
`NO_CONNECTION_SLOT` failure means the adapter is full, not that anything is broken.

## Advertisements are unauthenticated

Anything in radio range can advertise any name, service UUID, or manufacturer data. Advertised
content proves nothing about a device's identity. See [safety.md](safety.md).
