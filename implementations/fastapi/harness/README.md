# Harness — FastAPI 프로젝트 구조·네이밍 규칙 검사

`docs/`(공통) + `docs/architecture/*.md`(FastAPI 구현)의 가이드 규칙 중 **기계 검증 가능한 항목**을 외부 FastAPI 프로젝트에 적용하는 정적 분석 도구. 설계 원칙은 루트 [`../../../docs/harness.md`](../../../docs/harness.md)를 따른다 — 아키텍처 규칙 준수만 평가하고, `examples/`의 Account 도메인 같은 특정 업무 도메인 지식을 전제로 삼지 않는다.

## 구조

```
harness/
  harness.py                     CLI 엔트리 — 규칙 목록 정의 + 결과 집계/출력
  rules/
    __init__.py
    common.py                    공통 타입(Finding, RuleResult)과 헬퍼(collect_py_files, read, rel, norm, ...)
    file_naming.py                규칙별 구현 모듈 (규칙 하나당 파일 하나)
    repository_abc.py
    repository_impl.py
    handler_placement.py
    domain_purity.py
    directory_structure.py
    shared_infra.py
    event_placement.py
    layer_dependency.py
    no_notification_dependency_in_command.py
    outbox_drain_order.py
  tests/
    test_rules.py                pytest — 아래 fixtures/를 파라미터화해서 순회
    fixtures/<rule>/good/         해당 규칙을 통과해야 하는 최소 fixture
    fixtures/<rule>/bad-*/        해당 규칙을 위반해 실패해야 하는 fixture
```

각 규칙 모듈은 `check(root: str, py_files: list[str]) -> RuleResult` 시그니처를 가지며, `harness.py`의 `RULES` 목록에 등록된 순서대로 실행·출력된다. `py_files`는 `harness.py`가 한 번만 계산해 모든 규칙에 전달한다.

## 사용

```bash
# 저장소 루트에서
bash implementations/fastapi/harness.sh <projectRoot>

# 또는 harness/ 디렉토리에서 직접
cd implementations/fastapi/harness
python3 harness.py <projectRoot>
```

## 규칙 목록

| 이름 | 모듈 | 역할 |
|------|------|------|
| `file-naming` | `rules/file_naming.py` | 파일명이 `snake_case.py`인지 |
| `repository-abc` | `rules/repository_abc.py` | ABC Repository(`ABC`/`abstractmethod` + `Repository`) → `domain/` |
| `repository-impl` | `rules/repository_impl.py` | Repository 구현체(`class XRepository`) → `infrastructure/` |
| `handler-placement` | `rules/handler_placement.py` | `*_handler.py` → `application/command/` 또는 `application/query/` |
| `domain-purity` | `rules/domain_purity.py` | `domain/`에서 `fastapi`/`sqlalchemy`/`aioboto3` import 금지 |
| `directory-structure` | `rules/directory_structure.py` | `src/<domain>/{domain,application,interface,infrastructure}` + `application/{command,query}` 존재 |
| `shared-infra` | `rules/shared_infra.py` | `OutboxRelay` 참조 시 `src/outbox/`에 `outbox_writer.py`/`outbox_relay.py` 존재 확인, `*task_queue*` 참조 시 `src/task-queue/` 배치 확인 |
| `event-placement` | `rules/event_placement.py` | `*_event_handler.py` → `application/event/`, `*_integration_event.py` → `application/integration-event/` |
| `layer-dependency` | `rules/layer_dependency.py` | AST 기반 — `application/`이 `infrastructure/`를 직접 import하면 실패(의존성 역전) |
| `no-notification-dependency-in-command` | `rules/no_notification_dependency_in_command.py` | Command Handler가 `NotificationService`(ABC 포함)를 직접 의존하면 실패 — Outbox 경유해야 함 |
| `outbox-drain-order` | `rules/outbox_drain_order.py` | `OutboxRelay`를 참조하는 Command Handler가 `save(...)` 호출 뒤에 `process_pending(...)`을 호출하는지(순서 포함) — domain-events.md의 핵심 불변식 |

## 회귀 테스트

```bash
cd implementations/fastapi/harness
python3 -m pytest tests/
```

각 규칙은 최소 `tests/fixtures/<rule>/good/`(통과해야 함)와 `tests/fixtures/<rule>/bad-*/`(실패해야 함) fixture로 검증된다.

새 규칙을 추가하거나 기존 규칙을 수정할 때는:
1. `rules/<rule>.py`에 로직 구현(또는 수정) — `check(root, py_files) -> RuleResult` 시그니처
2. `tests/fixtures/<rule>/good/`, `tests/fixtures/<rule>/bad-*/` fixture 작성
3. `tests/test_rules.py`의 파라미터화 목록에 fixture 추가(신규 규칙인 경우 `harness.py`의 `RULES`에도 등록)
4. `python3 -m pytest tests/`
