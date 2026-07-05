#!/usr/bin/env python3
"""FastAPI Harness — Python 프로젝트 구조·네이밍 규칙 검사
Usage: python harness.py <projectRoot>
"""
import os
import re
import sys

ROOT = sys.argv[1] if len(sys.argv) > 1 else "."
PASS_COUNT = 0
FAIL_COUNT = 0

SNAKE_CASE = re.compile(r'^[a-z][a-z0-9]*(_[a-z0-9]+)*\.py$')


def passed(name: str) -> None:
    global PASS_COUNT
    PASS_COUNT += 1
    print(f"  PASS  {name}")


def failed(name: str, reason: str) -> None:
    global FAIL_COUNT
    FAIL_COUNT += 1
    print(f"  FAIL  {name} — {reason}")


def section(name: str) -> None:
    print(f"\n[{name}]")


def skip(name: str) -> None:
    print(f"  SKIP  {name}")


def collect_py_files() -> list[str]:
    result = []
    for dirpath, dirnames, filenames in os.walk(ROOT):
        dirnames[:] = [d for d in dirnames if d not in {".git", "__pycache__", ".venv", "venv", "node_modules"}]
        for name in filenames:
            if name.endswith(".py") and not name.endswith("_test.py") and name != "conftest.py":
                result.append(os.path.join(dirpath, name))
    return sorted(result)


def rel(path: str) -> str:
    return os.path.relpath(path, ROOT)


def read(path: str) -> str:
    try:
        with open(path, encoding="utf-8") as f:
            return f.read()
    except OSError:
        return ""


def norm(path: str) -> str:
    return path.replace(os.sep, "/")


py_files = collect_py_files()


# ── [1] 파일명 snake_case 검사 ───────────────────────────────
section("file-naming")

if not py_files:
    skip("Python 파일 없음")
else:
    for f in py_files:
        name = os.path.basename(f)
        if name in {"__init__.py", "conftest.py"}:
            continue
        r = rel(f)
        if SNAKE_CASE.match(name):
            passed(r)
        else:
            failed(r, "파일명은 snake_case.py 여야 함")


# ── [2] ABC Repository — domain/ 에만 위치 ──────────────────
section("repository-abc")

found_abc = False
for f in py_files:
    src = read(f)
    if "ABC" not in src and "abstractmethod" not in src:
        continue
    if "Repository" not in src:
        continue
    found_abc = True
    r = rel(f)
    if "/domain/" in norm(f):
        passed(f"{r} (Repository ABC)")
    else:
        failed(r, "ABC Repository는 domain/ 패키지 안에 있어야 함")

if not found_abc:
    skip("ABC Repository 정의 없음")


# ── [3] Repository 구현체 — infrastructure/ 에만 위치 ────────
section("repository-impl")

found_impl = False
for f in py_files:
    src = read(f)
    # ABC 없이 Repository를 상속하는 클래스 = 구현체
    if "Repository" not in src:
        continue
    # ABC 파일 자체는 제외
    if "abstractmethod" in src:
        continue
    # class XxxRepository 선언이 있으면서 ABC가 아닌 경우
    if not re.search(r'class\s+\w+Repository\b', src):
        continue
    found_impl = True
    r = rel(f)
    if "/infrastructure/" in norm(f):
        passed(f"{r} (Repository 구현체)")
    else:
        failed(r, "Repository 구현체는 infrastructure/ 패키지 안에 있어야 함")

if not found_impl:
    skip("Repository 구현체 없음")


# ── [4] Handler — application/command/ 또는 application/query/ ─
section("handler-placement")

found_handler = False
for f in py_files:
    name = os.path.basename(f)
    if not name.endswith("_handler.py"):
        continue
    found_handler = True
    r = rel(f)
    fn = norm(f)
    if "/application/command/" in fn or "/application/query/" in fn:
        passed(r)
    else:
        failed(r, "handler 파일은 application/command/ 또는 application/query/ 에 있어야 함")

if not found_handler:
    skip("handler 파일 없음")


# ── [5] domain/ 순수성 — FastAPI/SQLAlchemy import 금지 ───────
section("domain-purity")

found_domain = False
forbidden = re.compile(r'from fastapi|import fastapi|from sqlalchemy|import sqlalchemy')
for f in py_files:
    if "/domain/" not in norm(f):
        continue
    found_domain = True
    src = read(f)
    r = rel(f)
    if forbidden.search(src):
        failed(r, "domain/ 모듈에 fastapi/sqlalchemy import 금지")
    else:
        passed(f"{r} (domain 순수성)")

if not found_domain:
    skip("domain/ Python 파일 없음")


# ── summary ──────────────────────────────────────────────────
print("\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
if FAIL_COUNT == 0:
    print(f"{PASS_COUNT} passed  PASS")
else:
    print(f"{PASS_COUNT} passed, {FAIL_COUNT} failed  FAIL")
    sys.exit(1)
