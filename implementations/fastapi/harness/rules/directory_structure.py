"""[6] Directory structure check (4 layers + CQRS)"""

from __future__ import annotations

import os

from .common import SKIP_DIRS, RuleResult, failed, is_shared_dir, is_technical_service_dir, passed, skipped


def check(root: str, py_files: list[str]) -> RuleResult:
    result = RuleResult("directory-structure")
    src_dir = os.path.join(root, "src")
    if not os.path.isdir(src_dir):
        result.add(skipped("No src/ directory"))
        return result

    # __pycache__ etc. must be filtered separately, only for this function, which scans
    # directly via os.listdir — unlike the SKIP_DIRS used by collect_py_files(), this
    # function doesn't go through py_files and instead scans directly right under src/, so
    # a cache directory created by pytest could be mistaken for a domain folder and
    # incorrectly flagged as "missing layer directory."
    domains = [
        entry
        for entry in sorted(os.listdir(src_dir))
        if os.path.isdir(os.path.join(src_dir, entry)) and entry not in SKIP_DIRS and not is_shared_dir(entry)
    ]
    if not domains:
        result.add(skipped("No domain directory under src/"))
        return result

    for domain in domains:
        base = os.path.join(src_dir, domain)
        for layer in ("domain", "application", "interface", "infrastructure"):
            d = os.path.join(base, layer)
            label = f"src/{domain}/{layer}/"
            if os.path.isdir(d):
                result.add(passed(label))
            else:
                result.add(failed(label, "Directory not found"))
        if is_technical_service_dir(domain):
            continue
        for sub in ("command", "query"):
            d = os.path.join(base, "application", sub)
            label = f"src/{domain}/application/{sub}/"
            if os.path.isdir(d):
                result.add(passed(label))
            else:
                result.add(failed(label, "CQRS directory not found"))
    return result
