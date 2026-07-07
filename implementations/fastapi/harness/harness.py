#!/usr/bin/env python3
"""FastAPI Harness — Python 프로젝트 구조·네이밍 규칙 검사
Usage: python harness.py <projectRoot>
"""
import ast
import os
import re
import sys

ROOT = sys.argv[1] if len(sys.argv) > 1 else "."
PASS_COUNT = 0
FAIL_COUNT = 0

SNAKE_CASE = re.compile(r'^[a-z][a-z0-9]*(_[a-z0-9]+)*\.py$')
SKIP_DIRS = {".git", "__pycache__", ".venv", "venv", "node_modules"}
SKIP_FILES = {"__init__.py", "conftest.py"}


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
        dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
        for name in filenames:
            if name.endswith(".py") and not name.startswith("test_") and name not in SKIP_FILES:
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


def is_shared_dir(name: str) -> bool:
    return name in {"common", "database", "outbox", "task-queue", "config"}


py_files = collect_py_files()


# ── [1] 파일명 snake_case 검사 ───────────────────────────────
section("file-naming")

if not py_files:
    skip("Python 파일 없음")
else:
    for f in py_files:
        name = os.path.basename(f)
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
    if "Repository" not in src:
        continue
    if "abstractmethod" in src:
        continue
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
    if not name.endswith("_handler.py") or name.endswith("_event_handler.py"):
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
forbidden = re.compile(r'from fastapi|import fastapi|from sqlalchemy|import sqlalchemy|from aioboto3|import aioboto3')
for f in py_files:
    if "/domain/" not in norm(f):
        continue
    found_domain = True
    src = read(f)
    r = rel(f)
    if forbidden.search(src):
        failed(r, "domain/ 모듈에 fastapi/sqlalchemy/aioboto3 import 금지")
    else:
        passed(f"{r} (domain 순수성)")

if not found_domain:
    skip("domain/ Python 파일 없음")


# ── [6] 디렉토리 구조 검사 (4레이어 + CQRS) ─────────────────
section("directory-structure")

src_dir = os.path.join(ROOT, "src")
if not os.path.isdir(src_dir):
    skip("src/ 디렉토리 없음")
else:
    domains = []
    for entry in sorted(os.listdir(src_dir)):
        full = os.path.join(src_dir, entry)
        if os.path.isdir(full) and not is_shared_dir(entry):
            domains.append(entry)

    if not domains:
        skip("src/ 아래에 도메인 디렉토리 없음")
    else:
        for domain in domains:
            base = os.path.join(src_dir, domain)
            for layer in ("domain", "application", "interface", "infrastructure"):
                d = os.path.join(base, layer)
                label = f"src/{domain}/{layer}/"
                if os.path.isdir(d):
                    passed(label)
                else:
                    failed(label, "디렉토리 없음")
            for sub in ("command", "query"):
                d = os.path.join(base, "application", sub)
                label = f"src/{domain}/application/{sub}/"
                if os.path.isdir(d):
                    passed(label)
                else:
                    failed(label, "CQRS 디렉토리 없음")


# ── [7] shared-infra: outbox·task-queue ───────────────────────
# outbox 트리거는 "OutboxRelay를 실제로 참조하는 코드가 있는가"로 판단한다 — 이전에는
# "outbox 관련 파일이 src/outbox/ 밖에 있는가"로 판단했는데, 실제 파일들이 이미 전부
# src/outbox/ 안에 있어서 이 조건이 항상 거짓이 되어 "outbox 패턴 없음"이라는 잘못된
# SKIP 메시지를 내고 있었다 — Outbox가 완전히 구현되어 있는데도 "없음"이라고 보고한 것.
section("shared-infra")

uses_outbox_relay = any("OutboxRelay" in read(f) for f in py_files)
has_task_file = any(
    "task_queue" in os.path.basename(f) and "/task-queue/" not in norm(f)
    for f in py_files
)

if uses_outbox_relay:
    outbox_dir = os.path.join(src_dir, "outbox") if os.path.isdir(src_dir) else None
    if not outbox_dir or not os.path.isdir(outbox_dir):
        failed("src/outbox/", "OutboxRelay를 참조하지만 src/outbox/ 디렉토리가 없음")
    else:
        has_writer = os.path.isfile(os.path.join(outbox_dir, "outbox_writer.py"))
        has_relay = os.path.isfile(os.path.join(outbox_dir, "outbox_relay.py"))
        if has_writer and has_relay:
            passed("src/outbox/ (OutboxWriter/OutboxRelay 구현 확인)")
        else:
            missing = [n for n, ok in (("outbox_writer.py", has_writer), ("outbox_relay.py", has_relay)) if not ok]
            failed("src/outbox/", "src/outbox/ 디렉토리는 있으나 " + ", ".join(missing) + "를 찾을 수 없음")
else:
    skip("outbox 패턴 없음")

if has_task_file:
    task_dir = os.path.join(src_dir, "task-queue") if os.path.isdir(src_dir) else None
    if task_dir and os.path.isdir(task_dir):
        passed("src/task-queue/")
    else:
        failed("src/task-queue/", "task 파일이 있으나 src/task-queue/ 없음")
else:
    skip("task-queue 패턴 없음")


# ── [8] event-placement ──────────────────────────────────────
section("event-placement")

found_event = False
for f in py_files:
    name = os.path.basename(f)
    fn = norm(f)
    r = rel(f)

    if name.endswith("_event_handler.py"):
        found_event = True
        if "/application/event/" in fn:
            passed(r)
        else:
            failed(r, "이벤트 핸들러는 application/event/ 에 있어야 함")

    if name.endswith("_integration_event.py"):
        found_event = True
        if "/application/integration-event/" in fn:
            passed(r)
        else:
            failed(r, "integration event는 application/integration-event/ 에 있어야 함")

if not found_event:
    skip("이벤트 핸들러 없음")


# ── [9] layer-dependency: application/ → infrastructure/ import 금지 ─
# (의존성 역전 — application은 domain/의 추상 인터페이스(ABC/Technical Service
# 인터페이스)에만 의존해야 하며, infrastructure/의 구체 구현체를 직접 import하면
# 안 된다. docs/architecture/domain-service.md의 Technical Service 패턴 및
# layer-architecture.md의 의존 방향 규칙 참조.)
section("layer-dependency")

found_app = False
for f in py_files:
    fn = norm(f)
    if "/application/" not in fn:
        continue
    found_app = True
    r = rel(f)
    src = read(f)

    try:
        tree = ast.parse(src, filename=f)
    except SyntaxError as e:
        failed(r, f"파일을 파싱할 수 없음: {e}")
        continue

    violations = []
    for node in ast.walk(tree):
        if not isinstance(node, ast.ImportFrom):
            continue
        module = node.module or ""
        if "infrastructure" in module.split("."):
            names = ", ".join(alias.name for alias in node.names)
            violations.append(f"{'.' * node.level}{module} 에서 {names} import")

    if violations:
        failed(
            r,
            "application/ 은 infrastructure/ 구현체를 직접 import 할 수 없음 "
            "(의존성 역전 위반, domain/ 인터페이스에 의존해야 함): " + "; ".join(violations),
        )
    else:
        passed(f"{r} (layer-dependency)")

if not found_app:
    skip("application/ Python 파일 없음")


# ── [10] Command Handler는 Outbox 경유만 허용 — NotificationService 직접
#         의존 금지 (domain-events.md) ───────────────────────────
# layer-dependency(위 [9])는 import 경로만 보므로 application/service/의
# NotificationService(ABC)를 Command Handler가 직접 의존하는 dual-write 회귀를
# 잡지 못한다(ABC는 infrastructure/가 아니라서 그 규칙을 통과한다) — 별도로 검사한다.
section("no-notification-dependency-in-command")

found_cmd = False
notification_dep = re.compile(r'notification_service\s*:|:\s*NotificationService\b')
for f in py_files:
    fn = norm(f)
    if "/application/command/" not in fn:
        continue
    found_cmd = True
    r = rel(f)
    src = read(f)
    if notification_dep.search(src):
        failed(r, "Command Handler는 NotificationService(ABC 포함)를 직접 의존하지 않아야 함 — Outbox 경유(domain-events.md)")
    else:
        passed(f"{r} (Outbox 경유 확인)")

if not found_cmd:
    skip("Command Handler 없음")


# ── [11] Outbox 드레인 순서 — save() 호출 뒤에 process_pending() 호출 ──
section("outbox-drain-order")

found_order = False
save_call = re.compile(r'\.save\(')
process_pending_call = re.compile(r'\.process_pending\(')
for f in py_files:
    fn = norm(f)
    if "/application/command/" not in fn:
        continue
    src = read(f)
    if "OutboxRelay" not in src:
        continue
    found_order = True
    r = rel(f)
    save_match = save_call.search(src)
    pp_match = process_pending_call.search(src)
    if not save_match:
        failed(r, "OutboxRelay를 참조하지만 save(...) 호출을 찾을 수 없음")
    elif not pp_match:
        failed(r, "OutboxRelay를 참조하지만 process_pending() 호출이 없음 — 저장 직후 Outbox 드레인 누락(domain-events.md)")
    elif pp_match.start() < save_match.start():
        failed(r, "process_pending() 호출이 save(...) 호출보다 먼저 등장함 — 커밋 이후 드레인 순서 위반")
    else:
        passed(f"{r} (save → process_pending 순서 확인)")

if not found_order:
    skip("OutboxRelay를 사용하는 Command Handler 없음")


# ── summary ──────────────────────────────────────────────────
print("\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
if FAIL_COUNT == 0:
    print(f"{PASS_COUNT} passed  PASS")
else:
    print(f"{PASS_COUNT} passed, {FAIL_COUNT} failed  FAIL")
    sys.exit(1)
