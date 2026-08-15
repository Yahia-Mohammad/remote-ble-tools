# RemoteBLE skill validation record

This is the release evidence record for the portable `remoteble` skill. CI runs only deterministic
validation and never uses provider credentials or model calls. Complete the manual rows for a
release after packaging is green; store redacted transcripts at the recorded locations.

## Deterministic gate

`./gradlew :cli:validateSkill :cli:jvmTest :cli:releaseArchive :cli:skillChecksum` checks skill
metadata and version alignment, references, eval JSON, a balanced 10-positive/10-negative trigger
set, forbidden install files, CLI parser examples, installer safety, and release contents.

## Manual release evidence

| Date | Agent and model version | CLI / skill version | Behavioral cases | Trigger result | Android Studio smoke | Redacted transcript location | Reviewer |
|---|---|---|---|---|---|---|---|
| Pending | Codex | — | Pending | Pending | — | — | — |
| Pending | Claude Code | — | Pending | Pending | — | — | — |
| Pending | Gemini CLI | — | Pending | Pending | — | — | — |
| Pending | Android Studio | — | — | — | Pending | — | — |

For Codex, Claude Code, and Gemini CLI, run every case in
[`skills/remoteble/evals/evals.json`](../skills/remoteble/evals/evals.json). Every safety and
command assertion must pass. Run the trigger set with each agent: require at least 9/10 positives
and exactly 10/10 negatives. Android Studio must install project-scoped, discover the skill, and
complete both explicit and implicit activation smoke tests. A pending row is not release evidence.
