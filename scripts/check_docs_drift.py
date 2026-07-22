#!/usr/bin/env python3
"""Cross-checks documentation claims about file existence against the real repo tree.

Catches two failure modes seen repeatedly in this repo's history:
  1. A doc says a file/feature "doesn't exist yet" (아직 없다/미구현/부재/...) while the
     cited file actually exists on disk — the code moved on but the doc didn't.
  2. A doc says something "is already implemented"/"is the real code" (실제 코드/이미
     구현/이미 존재/...) while the cited file does not exist — the doc describes code
     that was never written, renamed, or removed.

This is a heuristic, path-existence checker, not a semantic one. It only looks at
backtick-quoted strings that look like file paths (contain a `/` and a file extension)
appearing on the same line as one of the keyword lists below, plus the header comment
of fenced code blocks. It will not catch claims that don't reference a concrete path
(e.g. "the app service isn't in docker-compose" with no `docker-compose.yml` backtick).

Exit code is non-zero if any contradiction is found (for CI gating).
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

DOC_GLOBS = [
    "docs/**/*.md",
    "implementations/*/docs/**/*.md",
    "implementations/*/CLAUDE.md",
    "CLAUDE.md",
]

# Keyword lists are intentionally narrow (high precision over high recall) — false
# positives here would train people to ignore the tool.
ABSENCE_KEYWORDS = [
    "아직 없다", "아직 없음", "아직 부재", "미구현", "존재하지 않는다",
    "존재하지 않고", "아직 도입하지", "아직 채택하지", "부재 —", "부재("
]
PRESENCE_KEYWORDS = [
    "실제 코드", "이미 구현", "실제로 존재", "이미 존재", "코드로 존재", "실제 존재",
]

# Header-comment markers inside fenced code blocks (first content line of ``` ... ```).
BLOCK_PRESENT_MARKERS = ["실제 코드"]
# Deliberately narrow: "추가 필요"/"제안"/"목표 형태" etc. are used constantly in this
# repo's docs to show a snippet that modifies an EXISTING file (adding a section to
# build.gradle, a method to an existing service) — not a claim that the whole file
# is missing. Only "아직 없음" (doesn't exist yet) is an unambiguous non-existence claim.
BLOCK_ABSENT_MARKERS = ["아직 없음"]

PATH_IN_BACKTICKS = re.compile(r"`([\w./{}\-]+/[\w./{}\-]+\.\w{1,30})(?!\w)`")
PATH_IN_COMMENT = re.compile(r"([\w./\-]+\.\w{2,30})(?!\w)")

COMMENT_PREFIXES = ("//", "#", "--", "<!--")

# Bare (no-slash) filenames that are legitimate to check even without a directory —
# these are well-known project-root/module-root filenames referenced by bare name
# throughout the docs. Anything else bare (like `AccountController.create`, a method
# reference) is ignored to avoid false positives.
KNOWN_BARE_FILES = {
    "build.gradle", "build.gradle.kts", "application.yml", "application.yaml",
    "docker-compose.yml", "docker-compose.yaml", "Dockerfile", "pom.xml", "go.mod",
    "go.sum", "package.json", "tsconfig.json", "requirements.txt", "pyproject.toml",
    ".env.example", ".env.development", ".gitignore", ".dockerignore",
    "logback-spring.xml", "settings.gradle", "settings.gradle.kts",
}


def build_file_index() -> list[str]:
    """All real file paths in the repo, relative to repo root, POSIX-style."""
    files: list[str] = []
    for p in REPO_ROOT.rglob("*"):
        if not p.is_file():
            continue
        if any(part in EXCLUDE_DIR_NAMES for part in p.relative_to(REPO_ROOT).parts):
            continue
        files.append(p.relative_to(REPO_ROOT).as_posix())
    return files


def resolves(cited_path: str, all_files: list[str]) -> bool:
    cited = cited_path[2:] if cited_path.startswith("./") else cited_path
    if not cited:
        return False
    needle = "/" + cited
    return any(f == cited or ("/" + f).endswith(needle) for f in all_files)


def looks_like_type_reference(path: str) -> bool:
    """`pkg/path.TypeName` (Go/Java/Kotlin qualified type) gets mis-parsed as a file
    path by our regexes since it has a slash and a dot. Real file extensions in this
    repo are always lowercase; a capital letter right after the last dot means this
    is a type reference, not a path."""
    tail = path.rsplit(".", 1)[-1]
    return bool(tail) and tail[0].isupper()


def iter_doc_files() -> list[Path]:
    seen: set[Path] = set()
    for pattern in DOC_GLOBS:
        for p in REPO_ROOT.glob(pattern):
            if p.is_file() and not any(part in EXCLUDE_DIR_NAMES for part in p.relative_to(REPO_ROOT).parts):
                seen.add(p)
    return sorted(seen)


def check_prose_lines(text: str, all_files: list[str]) -> list[tuple[int, str, str]]:
    """Returns (line_no, kind, message) for contradictions found in plain prose lines.

    Docs in this repo conventionally separate clauses with an em dash (—); a keyword
    and a path are only cross-referenced if they land in the same dash-delimited
    clause, which avoids most "the keyword actually predicates a different noun
    later in the same paragraph" false positives.
    """
    findings = []
    in_fence = False
    for i, line in enumerate(text.splitlines(), start=1):
        stripped = line.strip()
        if stripped.startswith("```"):
            in_fence = not in_fence
            continue
        if in_fence:
            continue
        if "..." in line:
            # ellipsis-abbreviated paths (`examples/.../foo.py`) aren't literal paths
            line = re.sub(r"`[^`]*\.\.\.[^`]*`", "", line)

        for clause in line.split("—"):
            paths = PATH_IN_BACKTICKS.findall(clause)
            paths = [p for p in paths if not p.endswith(".md")]
            if not paths:
                continue

            has_absence = any(k in clause for k in ABSENCE_KEYWORDS)
            has_presence = any(k in clause for k in PRESENCE_KEYWORDS)
            if not has_absence and not has_presence:
                continue

            for path in paths:
                if looks_like_type_reference(path):
                    continue
                exists = resolves(path, all_files)
                if has_absence and exists:
                    findings.append((i, "STALE-ABSENCE",
                                      f"doc claims `{path}` doesn't exist yet, but it does"))
                if has_presence and not exists:
                    findings.append((i, "PHANTOM-PRESENCE",
                                      f"doc claims `{path}` is real/implemented, but no such file exists"))
    return findings


def check_code_block_headers(text: str, all_files: list[str]) -> list[tuple[int, str, str]]:
    findings = []
    lines = text.splitlines()
    i = 0
    while i < len(lines):
        if lines[i].strip().startswith("```"):
            fence_line_no = i + 1
            # first non-empty content line of the block
            j = i + 1
            while j < len(lines) and lines[j].strip() == "":
                j += 1
            if j < len(lines) and not lines[j].strip().startswith("```"):
                header = lines[j].strip()
                if "..." in header:
                    header = ""
                if header.startswith(COMMENT_PREFIXES):
                    path_match = PATH_IN_COMMENT.search(header)
                    if path_match:
                        path = path_match.group(1)
                        if (not looks_like_type_reference(path)
                                and ("/" in path or path in KNOWN_BARE_FILES)):
                            exists = resolves(path, all_files)
                            is_present_marker = any(m in header for m in BLOCK_PRESENT_MARKERS)
                            is_absent_marker = any(m in header for m in BLOCK_ABSENT_MARKERS)
                            if is_present_marker and not is_absent_marker and not exists:
                                findings.append((fence_line_no, "PHANTOM-PRESENCE",
                                                  f"code block header claims `{path}` is real code, "
                                                  f"but no such file exists"))
                            if is_absent_marker and not is_present_marker and exists:
                                findings.append((fence_line_no, "STALE-ABSENCE",
                                                  f"code block header claims `{path}` is proposed/not-yet-added, "
                                                  f"but it already exists"))
            # advance past the closing fence
            k = j
            while k < len(lines) and not lines[k].strip().startswith("```"):
                k += 1
            i = k + 1
        else:
            i += 1
    return findings


def main() -> int:
    all_files = build_file_index()
    doc_files = iter_doc_files()

    total_findings = 0
    for doc_path in doc_files:
        text = doc_path.read_text(encoding="utf-8")
        rel = doc_path.relative_to(REPO_ROOT).as_posix()
        findings = check_prose_lines(text, all_files) + check_code_block_headers(text, all_files)
        findings.sort(key=lambda f: f[0])
        for line_no, kind, message in findings:
            print(f"{rel}:{line_no}: [{kind}] {message}")
            total_findings += 1

    print(f"\n{len(doc_files)} docs scanned, {len(all_files)} files indexed, "
          f"{total_findings} potential drift finding(s).")
    return 1 if total_findings else 0


if __name__ == "__main__":
    sys.exit(main())
