# AI Agent Working Guide

This doc holds the workflow and core principles for an AI agent designing/implementing a backend service using this playbook.
See `CLAUDE.md` for the doc index (keyword → file).

---

## Core principle

**Design first, implement later.**
Before writing any code, always read these two docs:

1. `docs/development-process.md` — which design deliverable needs to be produced first
2. Whichever doc under `docs/architecture/` is relevant to the task — to confirm the rules

If you can't remember a rule while implementing, don't write the code — read that doc first.

---

## Workflow by task type

### Adding a new domain

```
1. Requirements Analysis (RA)   → the Ubiquitous Language, a list of core use cases
2. Strategic Design (SD)       → the Bounded Context, the Context Map
3. Data Modeling (DM)     → identify the Aggregate, Entity, Value Object
4. Tactical Design (TD)       → the layer structure, the Repository interface, defining Command/Query
5. Test Design (TE)     → domain unit-test cases
6. Implementation (IM)            → write the code against the design deliverables
7. Verification (VA)            → run the harness + review the checklist
8. Doc Review (LA)       → confirm the design deliverables match the implementation
```

### Modifying a legacy feature

When modifying existing code, approach it as a Vertical Slice.
Don't refactor the whole thing at once — clean up just the slice you're modifying to match the 4-layer structure.
See `docs/development-process.md` → the "Modifying a legacy feature" section for the detailed procedure.

### Fixing a bug

1. Write a test that reproduces the bug
2. Find the root cause — first check whether it's a layer violation
3. Patch it with the smallest possible fix scope
4. Run the harness to confirm there's no structural regression

---

## Rules you must never violate

| Rule | Grounding doc |
|------|-----------|
| The domain/ layer must never import an external library/framework | `layer-architecture.md` |
| Business rules live only inside an Aggregate Root's methods | `tactical-ddd.md` |
| The Repository interface goes in domain/, the implementation in infrastructure/ | `repository-pattern.md` |
| An Application Service only coordinates — it never carries out business logic itself | `layer-architecture.md` |
| A Domain Event → publish through the Outbox (never publish directly) | `domain-events.md` |
| A DB change + publishing an event happen in the same transaction (no dual-write) | `domain-events.md` |
| No hard delete — soft delete via deletedAt | `repository-pattern.md` |
| An error is typed as an enum — never a free-form string | `error-handling.md` |

---

## Verification after finishing an implementation

### 1. Run the harness

Each implementation's harness checks structure, placement, and annotation rules all together.

```bash
bash implementations/<lang>/harness.sh <projectRoot>
```

If there's a FAIL, move that file to the correct layer and rerun.

| Implementation | How to run |
|--------|-----------|
| NestJS | `bash implementations/nestjs/harness.sh <root>` |
| Go | `bash implementations/go/harness.sh <root>` |
| Spring Boot (Java) | `bash implementations/java-springboot/harness.sh <root>` |
| Kotlin Spring Boot | `bash implementations/kotlin-springboot/harness.sh <root>` |
| FastAPI | `bash implementations/fastapi/harness.sh <root>` |

### 2. Review the checklist

Open `docs/checklist.md` and go through STEP 1 through 10 in order.
If the task included a design deliverable, also check STEP 11.

---

## How to navigate the docs

Find the task keyword in `CLAUDE.md`'s table → read that doc → implement it.

If you run into something you don't know, don't write code based on a guess — find and read the relevant doc first.
If it's not covered in any doc, implement it the simplest way possible, and ask a human to confirm when a design judgment call is needed.
