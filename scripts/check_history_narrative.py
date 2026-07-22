#!/usr/bin/env python3
"""Greps the repo for history-narrative language the project's standing convention forbids.

This repo's docs and code comments must describe the *current* architecture, not narrate
how it got there. Two categories recur (see memory/commit history of this repo):

  1. Session/issue meta-narration: referencing a specific past session, review pass, or
     issue number as the reason something looks the way it does (e.g. "a real past
     regression (#181)", "in a previous session").
  2. Before/after temporal framing: describing the current design by contrasting it with
     a prior state ("(new)"/"(implemented)" tags, "completely replaced", "no precedent").

Both read fine in a PR description or commit message — that's where this information
belongs. In docs/comments it rots (the "before" state stops being true background and
becomes stale trivia) and it recurs easily because *explaining why something changed*
naturally invites before/after phrasing. This script is the standard finishing-step grep
that past cleanup rounds ran by hand; see ALLOWLIST below for the small number of known
false positives (forward-looking design-scope notes that happen to contain a trigger word,
not narration of a past session).

Note on scope: this used to match a broader set of Korean phrases before the repo's docs
and code were translated to English. Several equally literal English equivalents (e.g.
"used to be", "no longer", "already implemented", "promoted to") turned out to be common,
legitimate phrasing in ordinary English technical prose in this repo (describing a current
semantic fact or a design decision's rationale, not narrating removed history), so
including them produced far more false positives than their Korean counterparts ever did.
The pattern list below is intentionally narrower and biased toward unambiguous markers
(explicit issue/session references, parenthetical status tags) rather than free-form
temporal phrases.

Exit code is non-zero if any non-allowlisted match is found (for CI gating).
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent

EXCLUDE_DIR_NAMES = {
    ".git", "node_modules", ".venv", "venv", "__pycache__", "build", "dist",
    "target", ".gradle", "bin", "obj", ".pytest_cache", "htmlcov",
    ".mypy_cache", "coverage", ".next", "out", ".idea", ".vscode", ".claude",
}

INCLUDE_SUFFIXES = {".md", ".ts", ".go", ".java", ".kt", ".py", ".yml", ".yaml"}

# Category 1: session/issue meta-narration.
CATEGORY_1 = re.compile(
    "|".join([
        r"\bprevious\s+session\b", r"\bprior\s+session\b", r"\blast\s+session\b",
        r"\bprevious\s+version\b", r"\bprior\s+version\b",
        r"\bprevious\s+audit\b", r"\bprior\s+audit\b",
        r"\blast\s+round\b", r"\bprevious\s+round\b",
        r"\bregression\s*#\d+", r"\bissue\s*#\d+",
    ]),
    re.IGNORECASE,
)

# Category 2: before/after temporal framing and completion labels.
CATEGORY_2 = re.compile(
    "|".join([
        r"\(new\)", r"\(no\s*change\)", r"\(implemented\)",
        r"\bcompletely\s+replaced\b", r"\bfirst\s+real\b", r"\bno\s+precedent\b",
    ]),
    re.IGNORECASE,
)

# (relative path, substring of the offending line) — genuine false positives, each with
# a reason a human already checked. Keep this list short; a new hit should almost always
# be fixed, not allowlisted.
ALLOWLIST: list[tuple[str, str]] = [
    # Forward-looking scope note ("no precedent for it in the actual code, so we don't
    # handle it") — not narrating a past session, describing a design-scope decision.
    ("implementations/go/harness/no_direct_env_access.go", "no precedent for it in this repository's actual code"),
]


SELF_PATH = Path(__file__).resolve()


def iter_files() -> list[Path]:
    files = []
    for path in REPO_ROOT.rglob("*"):
        if not path.is_file():
            continue
        if path.resolve() == SELF_PATH:
            continue
        if path.suffix not in INCLUDE_SUFFIXES:
            continue
        if any(part in EXCLUDE_DIR_NAMES for part in path.relative_to(REPO_ROOT).parts):
            continue
        files.append(path)
    return files


def is_allowlisted(rel_path: str, line: str) -> bool:
    return any(rel_path == p and needle in line for p, needle in ALLOWLIST)


def main() -> int:
    findings: list[tuple[str, int, str, str]] = []

    for path in iter_files():
        rel_path = str(path.relative_to(REPO_ROOT))
        try:
            text = path.read_text(encoding="utf-8")
        except (UnicodeDecodeError, OSError):
            continue

        for lineno, line in enumerate(text.splitlines(), start=1):
            category = None
            if CATEGORY_1.search(line):
                category = "session/issue narration"
            elif CATEGORY_2.search(line):
                category = "before/after framing"
            if category is None:
                continue
            if is_allowlisted(rel_path, line):
                continue
            findings.append((rel_path, lineno, category, line.strip()))

    if not findings:
        files_scanned = len(iter_files())
        print(f"{files_scanned} files scanned, 0 history-narrative finding(s).")
        return 0

    print(f"{len(findings)} history-narrative finding(s):\n")
    for rel_path, lineno, category, line in findings:
        print(f"  {rel_path}:{lineno} [{category}]")
        print(f"    {line}")
    print(
        "\nRewrite these to describe the current design directly, without referencing a "
        "prior state, session, or issue number. If this is a genuine false positive "
        "(forward-looking design-scope note, not history narration), add it to ALLOWLIST "
        "in scripts/check_history_narrative.py with a one-line reason."
    )
    return 1


if __name__ == "__main__":
    sys.exit(main())
