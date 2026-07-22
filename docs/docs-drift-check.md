# Automated doc-vs-code drift check

`scripts/check_docs_drift.py` automatically catches two patterns where a doc misdescribes the actual state of the code:

1. **STALE-ABSENCE** — a doc says a file "doesn't exist yet" / "not implemented" / "missing," but the file actually already exists. This happens when a feature was added but the doc describing that fact was never updated.
2. **PHANTOM-PRESENCE** — a doc says a file is "the real code" / "already implemented," but the file doesn't actually exist. This happens when code was renamed/deleted, or a doc cited code that never existed in the first place as if it were real.

Both patterns are cases where a doc describes the actual code state backwards (e.g. describing an already-implemented feature as "not yet there," or citing nonexistent code as "the real code"). Rather than a human grepping for this every time, CI checks it continuously.

## How it works

- Scans every markdown file under `docs/`, `implementations/*/docs/`, and `implementations/*/CLAUDE.md`.
- Extracts paths from backtick-quoted strings shaped like `path/to/file.ext`, and from the comment on the first line of a code block (headers like `// path — actual code`).
- Checks whether each path exists in the repo's real file tree via suffix matching (so it still matches even if a doc uses a relative path that omits `examples/`).
- Only cross-checks a path against "doesn't exist yet" / "not implemented"-style keywords and "actual code" / "already implemented"-style keywords when they appear **within the same em-dash (—) span** — this reduces false positives from a different clause in the same paragraph happening to mention a different file with the same keyword.

## Running locally

```bash
python3 scripts/check_docs_drift.py
```

If it finds anything, it prints in the form `file:line: [STALE-ABSENCE|PHANTOM-PRESENCE] message` and exits with code 1. CI (`.github/workflows/docs-drift.yml`) runs this script whenever `**/*.md` or `implementations/*/examples/**` changes.

## Limitations (intentionally narrow scope)

This tool does purely string/path matching with no semantic understanding — it deliberately does not try to catch ambiguous signals that would lower precision:

- **It can't catch a claim with no path.** For example, a sentence like "the `app` service isn't in compose" with no concrete backtick-quoted path has nothing to cross-check against.
- **A suggestion to "add content to an existing file" is never flagged as STALE-ABSENCE.** Files like `build.gradle`/`application.yml`/`docker-compose.yml`/`main.go` almost always exist, so even if a phrase like "— needs to be added (suggestion)" is attached to them, that usually means "add this section to this file," not "this file itself doesn't exist." Only an unambiguous nonexistence claim like "doesn't exist yet" is judged as STALE-ABSENCE.
- **It doesn't validate the content of code snippets.** It only checks whether the file exists, not whether the exact code the doc shows is actually present inside that file.
- **It filters out `package.Type` notation (Go/Java/Kotlin) so it isn't mistaken for a path**, but this isn't perfect — if a new false-positive pattern shows up, add an exception to this script.

If you find a new false-positive pattern, extend this script's filter logic and update this doc's "Limitations" section too.
