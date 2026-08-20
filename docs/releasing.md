# Releasing

A release is one annotated tag. Everything after that is automated, so the work is in what happens
before the tag and in checking what came out afterwards.

## Prepare the version

Two files carry the version and both have to move together:

| File | Value | Example |
|---|---|---|
| `gradle.properties` | `remoteble.version` | `0.1.2-SNAPSHOT` |
| `skills/remoteble/SKILL.md` | `metadata.version` | `0.1.2` |

`:cli:validateSkill` refuses a build whose skill version does not describe the release version. It
is wired to every task whose name ends in `Test`, and to the release-artifact tasks, so a mismatch
fails all four build jobs rather than one check — the release stops before anything publishes. The
skill is what a coding agent reads to decide how to drive the CLI, so one claiming the wrong version
is a real defect, not bookkeeping.

Keep the `-SNAPSHOT` suffix in `gradle.properties`. The verify workflow resolves its version from
that file and strips the suffix, so packages are still built and checked under a release-shaped
version; the release workflow takes its version from the tag instead.

Open this as its own pull request. Merge it, then **wait for `verify` to pass on the merge commit**.
Tagging a commit whose checks have not finished means discovering a problem from a release run,
which is a much slower way to learn it.

## Tag

```sh
git checkout main && git pull
git tag -a v0.1.2 -m "remoteble 0.1.2"
git push origin v0.1.2
```

The tag pattern decides what publishes:

- **`vX.Y.Z`** — archives, Linux packages for both architectures, provenance and SBOM attestations,
  the GitHub Release, and the Homebrew formula pushed to the tap.
- **`vX.Y.Z-rc.N`** — archives and the GitHub prerelease only. Packages and the Homebrew job are
  skipped, so a candidate never exercises the tap push.

Anything else is rejected by the `metadata` job before a build starts.

## Check what came out

A green board is not the same as a good release: a release has published with every check passing
and no provenance on the artifacts most people install. Verify it the way someone installing it
would, from the published assets rather than the build directory.

```sh
gh release download v0.1.2 --dir /tmp/r && cd /tmp/r
shasum -a 256 -c checksums.txt
for f in remoteble-*.zip remoteble_*.deb remoteble-*.rpm; do
  gh attestation verify "$f" --repo Yahia-Mohammad/remote-ble-tools
done
brew update && brew upgrade remoteble && remoteble --version
```

`brew update` matters: Homebrew installs from its local clone of the tap, so a stale clone will
happily install the previous version and report success.

## When a release fails

Where it stopped decides the recovery.

**Before `publish` created the release.** No release object exists, so the tag is the only artifact.
Delete it, fix the cause, and tag again:

```sh
git push origin --delete v0.1.2 && git tag -d v0.1.2
```

**After the release exists.** Re-running the workflow is safe: `publish` re-downloads the published
assets and compares them against a rebuild, and the build is reproducible — the SBOM's serial number
is derived and its timestamp comes from `SOURCE_DATE_EPOCH`, which nfpm reads too. Deleting a
published release is the last resort, not the first move.

## Notes that are easy to rediscover the hard way

- The macOS jobs run on a self-hosted runner, which shares one `/opt/homebrew` with its owner's
  machine. Formula checks address the tap by name for that reason: with the real tap installed, a
  bare `remoteble` is ambiguous and `brew style` reports the rendered formula as a duplicate of the
  tapped one.
- Attestations, dependency review, and downloading release assets anonymously all require the
  repository to be public.
- The agent's macOS cask in the tap is updated by hand, and does not follow this repository's
  releases. See [`distribution-roadmap.md`](distribution-roadmap.md).
