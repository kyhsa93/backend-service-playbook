# Harness Design Principles

Each `implementations/<lang>/harness/` is an automated evaluator/linter that statically verifies whether that language's `examples/` actually follows the guidance in `docs/` (shared) + `implementations/<lang>/docs/` (language-specific). The implementation approach differs per language (TypeScript AST analysis, a Go program, bash+grep, Python AST analysis), but when adding or extending an evaluator/rule, follow the principles below regardless of language.

## Core principles

- **The business example in `examples/` (the Account domain, etc.) is an illustrative sample.** Don't pin a specific business domain as the "correct answer" — a harness rule must never assume "the Account domain must behave like this."
- **The harness evaluates compliance with architectural rules, not whether business logic is correct.** It targets only *structural* rules — layer placement, dependency direction, naming, transaction boundaries, the Outbox pattern, and the like.
- **Prefer assertion + evaluator-based partial scoring over pinning one single correct implementation.** Rather than asserting "this file must look exactly like this," check individually whether each rule is violated and sum the results — the premise is that multiple valid implementations can exist.
- **`docs/checklist.md` (and each language's `checklist.md`) is both a document for humans to read and a spec for evaluator implementations.** When adding a new checklist item, also consider whether it can be verified mechanically.

## Out of scope (what the harness does NOT check)

The harness does not evaluate knowledge of any specific business domain.

Examples: order cancellation, payment approval, inventory reservation, membership tier policy, account suspend/reactivate rules, etc.

This kind of content can appear in doc examples or in a runnable example under `examples/`, but **it must never become a required premise of a core harness rule.** For instance, "does the Outbox pattern load events within the same transaction" is something to check, but a domain rule like "can a suspended account only be reactivated from the SUSPENDED state" is verified through code review or domain unit tests, not the harness.

If this distinction gets blurred, a new evaluator can accidentally couple itself to one specific business domain (currently Account), and the harness degrades from a framework-agnostic, domain-agnostic architecture guide into an "Account-service-only linter."

## Related docs

- `docs/checklist.md` — self-review checklist (mostly auto-verified by the harness, with some items explicitly marked for manual verification)
- `implementations/<lang>/harness/` — the per-language evaluator implementation
- `implementations/<lang>/harness.sh` — the execution entry point (`bash implementations/<lang>/harness.sh <projectRoot>`)
