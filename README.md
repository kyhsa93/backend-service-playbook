# Backend Service Playbook

[![NestJS](https://github.com/kyhsa93/backend-service-playbook/actions/workflows/nestjs.yml/badge.svg)](https://github.com/kyhsa93/backend-service-playbook/actions/workflows/nestjs.yml)
[![Go](https://github.com/kyhsa93/backend-service-playbook/actions/workflows/go.yml/badge.svg)](https://github.com/kyhsa93/backend-service-playbook/actions/workflows/go.yml)
[![Java Spring Boot](https://github.com/kyhsa93/backend-service-playbook/actions/workflows/java-springboot.yml/badge.svg)](https://github.com/kyhsa93/backend-service-playbook/actions/workflows/java-springboot.yml)
[![Kotlin Spring Boot](https://github.com/kyhsa93/backend-service-playbook/actions/workflows/kotlin-springboot.yml/badge.svg)](https://github.com/kyhsa93/backend-service-playbook/actions/workflows/kotlin-springboot.yml)
[![FastAPI](https://github.com/kyhsa93/backend-service-playbook/actions/workflows/fastapi.yml/badge.svg)](https://github.com/kyhsa93/backend-service-playbook/actions/workflows/fastapi.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

A **framework- and language-agnostic** guide to DDD-based backend service design and implementation principles — with the same architecture implemented, verified, and kept in sync across **5 languages**: NestJS (TypeScript), Go, Spring Boot (Java), Spring Boot (Kotlin), and FastAPI (Python).

The core design docs use TypeScript for code examples, but the patterns themselves apply the same way in any language.
See `implementations/<lang>/` for the actual per-language/framework implementation guides, runnable examples, and tests. `docs/implementations/` is a coverage-audit report cross-checking the root principles against each language's docs.

## What makes this different from a typical example repo

- **Not just one implementation** — the same Account/Card/Payment domain model, the same architectural rules, and the same edge cases are implemented independently in 5 languages, so you can compare how a pattern (Repository, CQRS, Outbox, rate limiting, ...) actually looks in each ecosystem.
- **A compliance harness, not just docs.** Each implementation ships an automated evaluator (`harness.sh`) that mechanically scores whether the code actually follows the documented rules — layer placement, dependency direction, naming conventions, and more — with no human review needed.
- **A scaffolding generator per language** (`scripts/create-domain*`) that turns the documented reference template into real, harness-passing code for a brand-new domain in one command — a fast way to start a new service on top of this architecture, or to validate that the docs and the generator haven't drifted apart.
- **Reused as an AI-agent benchmark.** Because the harness is an objective, automated scorer, `docs/benchmark.md` repurposes it to measure whether an AI coding agent can find and apply this repo's documented conventions to a requirement it has never seen before — see that doc for actual benchmark runs and results.

## Layout

```
docs/
  architecture/     Core design principles (DDD, layering, Repository, CQRS, etc.)
  checklist.md      Self-review checklist after finishing an implementation
  conventions.md    Commit/branch/REST API conventions
  development-process.md  Design-to-implementation workflow (8 agent roles)
  harness.md         The harness's own design principles
  benchmark.md        The AI-agent architecture-compliance benchmark + past results
  docs-drift-check.md Automated stale-doc detection

implementations/
  nestjs/             NestJS (TypeScript) implementation guide + examples + harness
  go/                 Go implementation guide + examples + harness
  java-springboot/    Spring Boot (Java) implementation guide + examples + harness
  kotlin-springboot/  Kotlin Spring Boot implementation guide + examples + harness
  fastapi/            FastAPI (Python) implementation guide + examples + harness

CLAUDE.md           Doc index for AI agents (keyword → doc)
AGENTS.md           AI agent working guide (workflow, principles)
```

## Using the harness

Each implementation directory includes a harness. It checks structure, placement, and annotation rules all together.

| Implementation | How to run | Prerequisite |
|--------|-----------|-----------|
| NestJS | `bash implementations/nestjs/harness.sh <root>` | node |
| Go | `bash implementations/go/harness.sh <root>` | Go 1.22+ |
| Spring Boot (Java) | `bash implementations/java-springboot/harness.sh <root>` | none |
| Kotlin Spring Boot | `bash implementations/kotlin-springboot/harness.sh <root>` | none |
| FastAPI | `bash implementations/fastapi/harness.sh <root>` | python3 |

## Scaffolding a new domain

Each language has a generator that turns the documented reference template into real, harness-passing code for a new domain name. Run it from inside that language's directory:

| Implementation | Command (from `implementations/<lang>/`) |
|--------|-----------|
| NestJS | `node scripts/create-domain.js <Domain>` |
| Go | `cd scripts/create-domain && go run . <Domain>` |
| Spring Boot (Java) | `python3 scripts/create_domain.py <Domain>` |
| Kotlin Spring Boot | `python3 scripts/create_domain.py <Domain>` |
| FastAPI | `python3 scripts/create_domain.py <Domain>` |

See each language's `CLAUDE.md` for the exact flags (e.g. `--wire` to auto-register the new domain).

## Contributing

Issues and PRs are welcome. Before submitting a change to an implementation, run that language's harness (above) and see `docs/checklist.md` for the self-review checklist this repo's own conventions expect.

## License

[MIT](LICENSE)
