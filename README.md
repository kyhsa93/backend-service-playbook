# Backend Service Playbook

A **framework- and language-agnostic** guide to DDD-based backend service design and implementation principles.

The code examples use TypeScript, but the patterns themselves apply the same way to any language — Go, Java, Python, and so on.
See `implementations/<lang>/` for the actual per-language/framework implementation guides. `docs/implementations/` is a coverage-audit report between the root principles and each language's docs.

---

## Layout

```
docs/
  architecture/     Core design principles (DDD, layering, Repository, CQRS, etc.)
  checklist.md      Self-review checklist after finishing an implementation
  conventions.md    Commit/branch/REST API conventions
  development-process.md  Design-to-implementation workflow (8 agent roles)

implementations/
  nestjs/             NestJS (TypeScript) implementation guide + examples + harness
  go/                 Go implementation guide + examples + harness
  java-springboot/    Spring Boot (Java) implementation guide + examples + harness
  kotlin-springboot/  Kotlin Spring Boot implementation guide + examples + harness
  fastapi/            FastAPI (Python) implementation guide + examples + harness

CLAUDE.md           Doc index for AI agents (keyword → doc)
AGENTS.md           AI agent working guide (workflow, principles)
```

---

## Using the harness

Each implementation directory includes a harness. It checks structure, placement, and annotation rules all together.

| Implementation | How to run | Prerequisite |
|--------|-----------|-----------|
| NestJS | `bash implementations/nestjs/harness.sh <root>` | node |
| Go | `bash implementations/go/harness.sh <root>` | Go 1.22+ |
| Spring Boot (Java) | `bash implementations/java-springboot/harness.sh <root>` | none |
| Kotlin Spring Boot | `bash implementations/kotlin-springboot/harness.sh <root>` | none |
| FastAPI | `bash implementations/fastapi/harness.sh <root>` | python3 |
