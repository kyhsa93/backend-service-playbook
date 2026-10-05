---
name: nestjs-architecture-harness
description: Static architecture linter for NestJS + TypeScript backends (DDD, CQRS) — layer dependencies, repository and aggregate rules, CQRS handlers, module DI, auth intent on routes, config validation, Dockerfile and bootstrap hygiene. Use after generating or refactoring NestJS code, before opening a PR, or when asked to review a NestJS project's architecture. Deterministic, local only — no LLM calls, no network.
---

# NestJS architecture harness

A rule-based linter for NestJS backend architecture, from
[backend-service-playbook](https://github.com/kyhsa93/backend-service-playbook). Every finding
has a `ruleId`, a severity, a file, and a `docRef` URL that explains the rule and the fix.

## Run

From the NestJS project root (dependencies installed — the harness uses the project's own
`typescript`):

```bash
bash <this-skill-dir>/scripts/run.sh .
```

Options:

- `--only=layer-dependency,repository-pattern` — run selected evaluators
- `--out=report.json` — write the JSON report to a file instead of stdout
- `HARNESS_ENABLE_TEST_RUN=1` — also run `npm test` (off by default: it executes project code)

Exit code `0` means no medium/high/critical finding, `1` means at least one, `2` means the
project's dependencies are not installed.

## Read the report

The JSON has `totalScore` (0–100), `grade`, `failures[]`, and `notApplicable[]`.

- `failures[].severity`: fix `critical` and `high` first. `low` entries are informational
  (for example `checklist.meta.coverage`, `test-run.skipped`) and never fail the run.
- `failures[].docRef`: open it before changing code. The rule's intent is there, and the fix
  is usually a move or a boundary change, not a rename.
- `notApplicable[]`: evaluators that encode the playbook's own folder layout or file naming
  (`src/<context>/{domain,application,interface,infrastructure}`, `<name>-module.ts`). They are
  skipped on projects that do not use that layout, so a plain NestJS project is not penalized
  for it. `build` is skipped until `node_modules/.bin/tsc` exists.

## Fix loop

1. Run the harness and group `failures` by `ruleId`.
2. For one `ruleId` at a time: read its `docRef`, fix every listed file, re-run with
   `--only=<evaluator>` to confirm.
3. Re-run the full harness. Stop when no `critical`/`high` remains, or report the rules you
   chose not to follow and why.

Do not silence a rule by renaming files or moving code into a folder the evaluator does not
scan; the rules check structure, so those changes only hide the dependency they flag.

## Scope

The harness checks architecture rules, not business logic. A high score does not mean the
code is correct, and a project can reasonably reject a rule — report that instead of forcing
it. Source, tests and the full rule list: `implementations/nestjs/harness/` in the playbook
repository. MIT licensed (see `LICENSE`).
