"""[22] aggregate-id-format: whether `generate_id()` uses 32-character hex with no hyphens
(`.hex`) (aggregate-id.md)

aggregate-id.md pins down the Aggregate ID as "a UUID v4 with the hyphens removed, as a
32-character hex string" — `uuid.uuid4().hex` is the correct approach, and
`str(uuid.uuid4())` (36 characters, hyphens included) is incorrect. This checks only the
single file that is this repository's common ID-issuing util (`src/common/generate_id.py`)
— not a broad scan, just the one point where the ID format is actually defined.
"""

from __future__ import annotations

import re

from .common import RuleResult, failed, passed, read, rel, skipped

CANDIDATE_PATHS = ("src/common/generate_id.py",)

HYPHENATED_UUID = re.compile(r"\bstr\s*\(\s*uuid\.?u{0,1}uid4\s*\(\s*\)\s*\)")
HEX_UUID = re.compile(r"uuid4?\s*\(\s*\)\s*\.\s*hex\b")


def _find_generate_id_file(root: str) -> str | None:
    import os

    for candidate in CANDIDATE_PATHS:
        path = os.path.join(root, candidate)
        if os.path.isfile(path):
            return path
    return None


def check(root: str, py_files: list[str]) -> RuleResult:
    del py_files  # a single-file check — the scan target is one fixed path relative to root
    result = RuleResult("aggregate-id-format")

    path = _find_generate_id_file(root)
    if path is None:
        result.add(skipped("src/common/generate_id.py not found"))
        return result

    label = rel(root, path)
    src = read(path)

    if "def generate_id" not in src:
        result.add(failed(label, "The generate_id() function is not defined (aggregate-id.md)"))
        return result

    if HYPHENATED_UUID.search(src):
        result.add(
            failed(
                label,
                "str(uuid.uuid4()) produces a 36-character string with hyphens included —"
                " 32-character hex with no hyphens (`uuid.uuid4().hex`) must be used (aggregate-id.md)",
            )
        )
        return result

    if HEX_UUID.search(src):
        result.add(passed(f"{label} (uuid4().hex — 32 characters, no hyphens)"))
    else:
        result.add(
            failed(
                label,
                "Cannot confirm that generate_id() uses uuid4().hex — it must be a"
                " 32-character hex format with no hyphens (aggregate-id.md)",
            )
        )
    return result
