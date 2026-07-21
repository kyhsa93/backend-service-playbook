#!/usr/bin/env python3
"""Greps the repo for history-narrative language the project's standing convention forbids.

This repo's docs and code comments must describe the *current* architecture, not narrate
how it got there. Two categories recur (see memory/commit history of this repo):

  1. Session/issue meta-narration: referencing a specific past session, review pass, or
     issue number as the reason something looks the way it does (e.g. "과거 실제 회귀(#181)",
     "이전 세션에서").
  2. Before/after temporal framing: describing the current design by contrasting it with
     a prior state ("예전에는 X였다", "더 이상 X가 아니다", "(신규)"/"(구현됨)" tags,
     "이미 적용 완료되었다").

Both read fine in a PR description or commit message — that's where this information
belongs. In docs/comments it rots (the "before" state stops being true background and
becomes stale trivia) and it recurs easily because *explaining why something changed*
naturally invites before/after phrasing. This script is the standard finishing-step grep
that past cleanup rounds ran by hand; see ALLOWLIST below for the small number of known
false positives (forward-looking design-scope notes that happen to contain a trigger word,
not narration of a past session).

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
    ".mypy_cache", "coverage", ".next", "out", ".idea", ".vscode",
}

INCLUDE_SUFFIXES = {".md", ".ts", ".go", ".java", ".kt", ".py"}

# Category 1: session/issue meta-narration.
CATEGORY_1 = re.compile(
    "|".join([
        r"이번\s*패스", r"이전\s*세션", r"이전\s*버전", r"이전\s*감사",
        r"지난\s*세션", r"지난\s*라운드", r"회귀\s*#\d+", r"과거\s*실제",
    ])
)

# Category 2: before/after temporal framing and completion labels.
CATEGORY_2 = re.compile(
    "|".join([
        r"예전에는", r"과거에는", r"이전에는", r"더\s*이상.{0,10}아니",
        r"바뀌었다", r"시절", r"전례가", r"\(신규\)", r"\(변경\s*없음\)",
        r"\(구현됨\)", r"승격되었다", r"완전히\s*교체", r"첫\s*실",
        r"적용\s*완료", r"이미\s*적용됨", r"이미\s*구현됨", r"이미\s*올바름",
    ])
)

# (relative path, substring of the offending line) — genuine false positives, each with
# a reason a human already checked. Keep this list short; a new hit should almost always
# be fixed, not allowlisted.
ALLOWLIST: list[tuple[str, str]] = [
    # Forward-looking scope note ("no precedent of this in the actual code, so we don't
    # handle it") — not narrating a past session, describing a design-scope decision.
    ("implementations/go/harness/no_direct_env_access.go", "전례가 없어 다루지 않는다"),
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
        if any(part in EXCLUDE_DIR_NAMES for part in path.parts):
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
