"""[6] 디렉토리 구조 검사 (4레이어 + CQRS)"""

from __future__ import annotations

import os

from .common import RuleResult, failed, is_shared_dir, is_technical_service_dir, passed, skipped


def check(root: str, py_files: list[str]) -> RuleResult:
    result = RuleResult("directory-structure")
    src_dir = os.path.join(root, "src")
    if not os.path.isdir(src_dir):
        result.add(skipped("src/ 디렉토리 없음"))
        return result

    domains = [
        entry
        for entry in sorted(os.listdir(src_dir))
        if os.path.isdir(os.path.join(src_dir, entry)) and not is_shared_dir(entry)
    ]
    if not domains:
        result.add(skipped("src/ 아래에 도메인 디렉토리 없음"))
        return result

    for domain in domains:
        base = os.path.join(src_dir, domain)
        for layer in ("domain", "application", "interface", "infrastructure"):
            d = os.path.join(base, layer)
            label = f"src/{domain}/{layer}/"
            if os.path.isdir(d):
                result.add(passed(label))
            else:
                result.add(failed(label, "디렉토리 없음"))
        if is_technical_service_dir(domain):
            continue
        for sub in ("command", "query"):
            d = os.path.join(base, "application", sub)
            label = f"src/{domain}/application/{sub}/"
            if os.path.isdir(d):
                result.add(passed(label))
            else:
                result.add(failed(label, "CQRS 디렉토리 없음"))
    return result
