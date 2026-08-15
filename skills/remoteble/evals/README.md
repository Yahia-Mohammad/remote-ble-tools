# RemoteBLE skill evaluation protocol

These evaluation files are source-only: they are excluded from installed skills and release skill
ZIPs. CI validates their JSON and balance but never calls a model, provider, or RemoteBLE endpoint.

## Provider-neutral manual run

Use a clean, isolated home and a fresh project copy for each provider. Install the skill with the
CLI under test, then run every prompt in [`evals.json`](evals.json) against a controlled RemoteBLE
agent or simulator. Capture the shell commands, stdout, stderr, exit codes, and final response.
Grade the recorded transcript against every assertion; do not grade an agent's self-summary.

Run the 20 prompts in [`trigger-evals.json`](trigger-evals.json) with the same agent/model and
record whether the skill was loaded. A release passes only when each of Codex, Claude Code, and
Gemini CLI passes all behavioral assertions, triggers at least 9 of 10 positives, and excludes all
10 negatives. Then install project-scoped into Android Studio and smoke-test discovery plus explicit
and implicit activation.

## Evidence template

Record one row per agent in [`docs/skill-validation.md`](../../../docs/skill-validation.md): date,
provider and model versions, CLI/skill versions, behavioral score, trigger score, Android result
when relevant, reviewer, and a redacted transcript location. Do not commit credentials, endpoint
tokens, unredacted device identifiers, or provider session data.
