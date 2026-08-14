# Skill evals

Three task prompts for checking whether an agent driving `remoteble` behaves the way
[`../SKILL.md`](../SKILL.md) says it should. Each is run twice — once with the skill supplied, once
cold — and graded from the transcript.

## Running them

Start the pinned agent radio-less, the same way `LiveAgent.kt` does:

```bash
REMOTE_BLE_TOKENS="primary=primary-secret-token,secondary=secondary-secret-token" \
REMOTE_BLE_OPERATOR_TOKEN=operator-secret-token \
REMOTE_BLE_POLICY_FILE=integration-tests/src/test/resources/acceptance-policy.json \
  java -jar integration-tests/build/live-agent/remoteble-agent-0.11.0-all.jar \
  --bind 127.0.0.1 --port 8099 \
  --simulate integration-tests/src/test/resources/sim-acceptance.json
```

Give each run an isolated `HOME` and `REMOTE_BLE_LOG_DIR` so identities and leases do not leak
between them, and have it record every command with its exit code. **Grade from that transcript,
not from the runner's own account of what it did** — the two diverged in practice.

## What the first round found (2026-08-11)

One round of three prompts, one run per condition. With-skill passed 14/14 assertions; the cold
baseline passed 13/14 and was 25–45% slower on every prompt.

Read that delta narrowly. The single assertion that separated them was preflight — the baseline
never ran `agent status`/`agent capabilities` before acting. The other two prompts did not
discriminate at all, for two different reasons worth keeping in mind when adding cases:

- **The CLI already refuses the dangerous thing.** Ambiguous selectors are rejected by the CLI
  itself (`Selector is ambiguous; 2 devices matched`), so an agent reaches the right answer with or
  without the skill.
- **The baseline was not clean.** It had filesystem access to this repository and found
  `docs/safety-model.md` and `references/safety.md` on its own. A cold run here is "no skill
  supplied", not "no documentation reachable".

An unscored difference showed up only by reading logs: the with-skill run released its leases with
`disconnect` and agent slots recovered; the baseline did not, and claimed in its report that leases
were released automatically, which is not how the state model works.

## Description triggering — prepared, not yet measured

[`trigger-evals.json`](trigger-evals.json) holds 20 queries — 10 that should load the skill, 10
near-misses that should not. The negatives are the ones that carry the information: Bluetooth
*audio* pairing, writing peripheral firmware, Web Bluetooth in a browser, a port scan, an unrelated
"remote agent", and a BLE question that only wants an explanation. A description that fires on those
is too greedy, and the `description` field is the only thing deciding whether the skill loads at
all.

Running it needs a logged-in `claude` CLI, because the optimizer shells out to `claude -p`:

```text
python -m scripts.run_loop \
  --eval-set skills/remoteble/evals/trigger-evals.json \
  --skill-path skills/remoteble \
  --model <model-id> --max-iterations 5 --verbose
```

**This has not been run.** The one attempt failed at the first `claude -p` call with
`Not logged in`, which made every query report as "did not trigger" — including the negatives, which
then passed vacuously. Treat any score from such a run as a harness failure, not a result: a real
run takes far longer than a few seconds, and a plausible baseline does not have 0% recall with 100%
precision.

Note that the optimizer **rewrites the `description` field in `SKILL.md`** when it finds a better
one. Run it on a clean tree so the change is reviewable.

## What is still untested

The prompts below exercise one-shot commands only. The skill's most distinctive claims have no
coverage yet, and are the obvious place to add cases:

- `session --jsonl` — out-of-order results correlated by `id`, and `session.close` being answered
  while in-flight work continues.
- Exit code 8 (indeterminate) and the never-blind-retry rule. Needs fault injection.
- Untrusted device text under real pressure. The hostile advertised name in the profile is passive;
  nothing yet tries to induce an agent to act on it.
- Capability gating — `descriptor read` returns exit 7 against this agent and no prompt goes near
  it.
