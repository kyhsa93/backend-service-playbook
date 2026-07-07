"""규칙 모듈 전체가 공유하는 타입/헬퍼.

각 규칙 모듈은 `check(root: str, py_files: list[str]) -> RuleResult` 시그니처를
갖는다. `py_files`는 harness.py가 한 번만 계산해 모든 규칙에 전달한다(원본
harness.py가 전역에서 한 번 `collect_py_files()`를 호출하던 것과 동일).
"""
from __future__ import annotations

import os

SKIP_DIRS = {".git", "__pycache__", ".venv", "venv", "node_modules"}
SKIP_FILES = {"__init__.py", "conftest.py"}


class Finding:
    def __init__(self, kind: str, name: str, reason: str = "") -> None:
        self.kind = kind  # "pass" | "fail" | "skip"
        self.name = name
        self.reason = reason

    def __repr__(self) -> str:  # pragma: no cover - debugging aid only
        return f"Finding({self.kind!r}, {self.name!r}, {self.reason!r})"


class RuleResult:
    def __init__(self, section: str, findings: list[Finding] | None = None) -> None:
        self.section = section
        self.findings: list[Finding] = findings or []

    def add(self, finding: Finding) -> None:
        self.findings.append(finding)

    def count(self, kind: str) -> int:
        return sum(1 for f in self.findings if f.kind == kind)


def passed(name: str) -> Finding:
    return Finding("pass", name)


def failed(name: str, reason: str) -> Finding:
    return Finding("fail", name, reason)


def skipped(message: str) -> Finding:
    return Finding("skip", message)


def collect_py_files(root: str) -> list[str]:
    result = []
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
        for name in filenames:
            if name.endswith(".py") and not name.startswith("test_") and name not in SKIP_FILES:
                result.append(os.path.join(dirpath, name))
    return sorted(result)


def rel(root: str, path: str) -> str:
    return os.path.relpath(path, root)


def read(path: str) -> str:
    try:
        with open(path, encoding="utf-8") as f:
            return f.read()
    except OSError:
        return ""


def norm(path: str) -> str:
    return path.replace(os.sep, "/")


def is_shared_dir(name: str) -> bool:
    return name in {"common", "database", "outbox", "task-queue", "config"}
