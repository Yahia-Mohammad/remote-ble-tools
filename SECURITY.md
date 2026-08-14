# Security policy

## Supported versions

Before `v0.1.0` is released, security fixes apply to the current `main` branch. After release, the
latest v0.1 patch release will be supported until the next minor release.

## Reporting a vulnerability

Do not disclose vulnerabilities involving credentials, remote-agent authorization, write-policy
bypass, audit redaction, or unsafe hardware operations in a public issue.

Use the repository's **Private vulnerability reporting** feature once it is enabled. Include a
minimal reproduction, affected CLI and agent versions, the operating systems involved, and any
safe redacted logs. Do not include bearer tokens, operator credentials, or real write payloads.

Pull requests are checked by a read-only full-history secret scan and dependency review. These are
defense-in-depth checks, not a substitute for keeping credentials out of commands, logs, issues,
and commits.

If private reporting is not available yet, contact the repository maintainer through the verified
contact method displayed on the repository profile and request a private channel. We will
acknowledge a valid report, assess the affected versions, and coordinate a fix before public
disclosure.
