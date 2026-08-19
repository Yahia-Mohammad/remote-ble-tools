# Distribution roadmap

Three distribution capabilities are deliberately deferred. Each one is recorded here with what it
buys, what it costs, and the point at which deferring it stops being reasonable — so the decision
can be revisited on evidence rather than rediscovered.

## Automate the Homebrew cask for the agent

The CLI installs from a Homebrew formula this repository's release workflow renders and pushes to
`Yahia-Mohammad/homebrew-tap`. The RemoteBLE agent installs from a cask pointing at a macOS `.app`
bundle published by [`remote-ble`](https://github.com/Yahia-Mohammad/remote-ble). That cask is
updated by hand: each agent release changes the version and the archive checksum.

Automating it means the agent's release workflow writes to the tap, which requires a second
fine-grained token with `Contents: write` on the tap, held as a secret in a *different* repository
from the one that already has one. That is the same class of cross-repository credential path that
silently did nothing here until it was exercised deliberately — so whatever automates it should
ship with the equivalent of `tap-credential-check.yml`, proving the credential before a release
depends on it, rather than discovering the failure at the last step of a publish.

**Revisit when** agent releases become frequent enough that a manual tap commit is routinely
forgotten, which shows up as the tap advertising a version the release page no longer offers.

## Serve deb and rpm from an apt/yum repository

Packages are published as GitHub Release assets, verified by `checksums.txt` and build provenance
attestations. Installing is `sudo apt install ./remoteble_X.Y.Z_amd64.deb`, which works and is
fully verifiable, but is a download rather than a subscription: `apt upgrade` will never offer a
new version.

GitHub Packages cannot close that gap — it hosts npm, RubyGems, Maven, Gradle, NuGet, and Docker,
with no Debian, RPM, or generic binary registry. The options are a self-hosted apt/yum repository
(GitHub Pages can serve the metadata), a hosted service such as Cloudsmith, or eventual inclusion
in the distributions themselves.

The real cost of self-hosting is not the metadata generation; it is the GPG signing key. A
repository signing key lives in CI, has to be protected and rotated, and its compromise is worth
more to an attacker than any single release artifact. That is a larger commitment than the
publishing token this project already carries, and it should be made deliberately.

**Revisit when** users start asking for upgrades rather than downloads, or when the release cadence
makes manual reinstallation the common path rather than the occasional one.

## Sign the macOS agent with a Developer ID

The agent's macOS bundle is ad-hoc signed, matching what `agent-rs/run-agent-rs.sh` assembles
locally. macOS grants Bluetooth access through TCC, and TCC keys a grant to the code signature. An
ad-hoc signature is a new identity on every build, so **the Bluetooth permission prompt returns
after every upgrade** — the grant is not lost through any fault of the user, it simply does not
apply to what is now a different application.

A Developer ID signature makes that identity stable across releases, and notarization additionally
removes the Gatekeeper friction of an unidentified developer. Both require an Apple Developer
account and certificate material held as CI secrets.

Signing plumbing is worth building only once it can be exercised, for the reason the rest of this
file keeps returning to: an unexercised credential path reports success until the moment it
matters.

**Revisit when** the agent is installed by people who did not build it, for whom re-approving
Bluetooth after each upgrade reads as a defect rather than a quirk.
