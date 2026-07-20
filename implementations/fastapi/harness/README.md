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
    repository_naming.py
    handler_placement.py
    domain_purity.py
    directory_structure.py
    shared_infra.py
    event_placement.py
    layer_dependency.py
    no_notification_dependency_in_command.py
    outbox_no_sync_drain.py
    cqrs_pattern.py
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
| `repository-naming` | `rules/repository_naming.py` | `domain/`의 `*Repository`/`*Query` ABC 추상 메서드명이 `find_by_*`/`find_all`/`count*`/bare `save`/bare `delete`/`update_*` 안티패턴이면 실패 — `find_<noun>s`/`save_<noun>`/`delete_<noun>`만 허용, 별도 `update_*` 메서드 금지(repository-pattern.md) |
| `handler-placement` | `rules/handler_placement.py` | `*_handler.py` → `application/command/` 또는 `application/query/` |
| `domain-purity` | `rules/domain_purity.py` | `domain/`에서 `fastapi`/`sqlalchemy`/`aioboto3`/`logging`/`structlog` import 금지 |
| `directory-structure` | `rules/directory_structure.py` | `src/<domain>/{domain,application,interface,infrastructure}` + `application/{command,query}` 존재 |
| `shared-infra` | `rules/shared_infra.py` | `OutboxWriter` 참조 시 `src/outbox/`에 `outbox_writer.py`/`outbox_poller.py`/`outbox_consumer.py` 존재 확인, `*task_queue*` 참조 시 `src/task-queue/` 배치 확인 |
| `event-placement` | `rules/event_placement.py` | `*_event_handler.py` → `application/event/`, `*_integration_event.py` → `application/integration-event/` |
| `layer-dependency` | `rules/layer_dependency.py` | AST 기반 — `application/`이 `infrastructure/`를 직접 import하면 실패(의존성 역전) |
| `no-notification-dependency-in-command` | `rules/no_notification_dependency_in_command.py` | Command Handler가 `NotificationService`(ABC 포함)를 직접 의존하면 실패 — Outbox 경유해야 함 |
| `outbox-no-sync-drain` | `rules/outbox_no_sync_drain.py` | Command Handler가 `OutboxRelay`/`OutboxPoller`/`OutboxConsumer`를 직접 참조하거나 `process_pending()`/`run_forever()`류를 호출하면 실패 — 저장 후 곧바로 반환해야 하며 Outbox → SQS 발행/수신은 독립적으로 주기 실행되는 Poller/Consumer만의 책임이다(domain-events.md, 동기 드레인 금지) |
| `cqrs-pattern` | `rules/cqrs_pattern.py` | `application/query/` 하위 파일이 쓰기용 Repository(`Repository` 문자열)를 참조하면 실패 — Query는 읽기 전용 Query 인터페이스에만 의존해야 함(cqrs-pattern.md) |
| `domain-layer-isolation` | `rules/domain_layer_isolation.py` | AST 기반, 경로 세그먼트 검사 — `domain/`이 (같은 도메인이든 다른 도메인이든) `application/`·`infrastructure/`·`interface/`를 import하면 실패. `domain-purity`의 프레임워크 이름 블록리스트보다 넓은 구조적 버전(layer-architecture.md) |
| `aggregate-no-public-setters` | `rules/aggregate_no_public_setters.py` | `domain/` 클래스에 public `@x.setter` 프로퍼티가 있으면 실패 — 상태 변경은 이름이 있는 도메인 메서드(`deposit()`, `suspend()` 등)로만 해야 함(tactical-ddd.md) |
| `no-cross-aggregate-reference` | `rules/no_cross_aggregate_reference.py` | `src/payment/domain/{payment.py,refund.py}` 전용 — `Payment`가 `Refund` 클래스를(또는 그 반대를) 필드/생성자 타입으로 직접 참조하면 실패, ID 참조(`payment_id: str`)만 허용(domain-service.md) |
| `no-direct-env-access-outside-config` | `rules/no_direct_env_access.py` | `domain/`·`application/`에서 `os.environ`/`os.getenv` 직접 호출 시 실패 — `config/`의 `BaseSettings`로 캡슐화해야 함(config.md) |
| `no-cross-bc-repository-in-application` | `rules/no_cross_domain_repository_import.py` | AST 기반 — `application/`이 다른 도메인의 `domain/repository.py`류 Repository/Query ABC를 직접 import하면 실패, `application/adapter/`의 Adapter를 거쳐야 함(cross-domain.md) |
| `scheduler-in-infrastructure-only` | `rules/scheduler_in_infrastructure_only.py` | `domain/`·`application/`에 APScheduler/Celery/`asyncio.create_task` 기반 스케줄링이 있으면 실패 — Scheduler는 `infrastructure/`에만 위치(scheduling.md). `src/outbox/`는 이미 infrastructure에 준하는 위치로 대상 밖 |
| `no-silent-except` | `rules/no_silent_except.py` | AST 기반 — `application/`·`infrastructure/`의 `except ...: pass`(본문이 정확히 `pass` 하나)가 있으면 실패, 로깅 후 전파해야 함(observability.md) |
| `dockerfile-conventions` | `rules/dockerfile_conventions.py` | `py_files`가 아니라 `<root>/Dockerfile`·`<root>/.dockerignore`를 직접 읽어 검사 — 멀티스테이지(`FROM` 2개 이상), `HEALTHCHECK` 존재, `.dockerignore` 존재+합리적 제외 패턴(container.md) |
| `aggregate-id-format` | `rules/aggregate_id_format.py` | `src/common/generate_id.py` 단일 파일 검사 — `uuid.uuid4().hex`(하이픈 없는 32자리)를 사용하는지, `str(uuid.uuid4())`(하이픈 포함 36자리) 안티패턴이 없는지(aggregate-id.md) |
| `error-response-schema` | `rules/error_response_schema.py` | `@app.exception_handler(...)`로 등록된 핸들러가 만드는 응답 바디(`JSONResponse(content=...)`)의 필드 집합이 `statusCode`/`code`/`message`/`error` 4개와 정확히 일치하는지 — dict 리터럴이든 빌더 함수(Pydantic 모델 `.model_dump()`) 경유든 재귀적으로 추적(error-handling.md) |
| `soft-delete-filter` | `rules/soft_delete_filter.py` | `infrastructure/persistence/`의 SQLAlchemy 모델 중 `updated_at`(상태 변경 가능)이 있는데 `deleted_at`이 없으면 실패, `deleted_at`이 있으면 그 모델을 조회하는 `find_*` 메서드가 `<Model>.deleted_at.is_(None)` 필터를 포함하는지 검사(persistence.md) |
| `typed-errors-only` | `rules/typed_errors_only.py` | `domain/`·`application/`에서 `raise Exception("...")`/`raise ValueError("...")` 등 내장 제네릭 예외에 문자열 메시지를 실어 던지면 실패 — `domain/errors.py`의 타입화된 예외 클래스를 raise해야 함(AGENTS.md, error-handling.md) |
| `rate-limit-wired` | `rules/rate_limit_wired.py` | `Limiter`가 정의만 되고 `main.py`에 배선(`app.state.limiter`/`RateLimitExceeded` 핸들러/`SlowAPIMiddleware`)되지 않았거나, 어떤 라우트에도 `@limiter.limit(...)`이 적용되지 않아 죽은 코드로 남아있으면 실패(rate-limiting.md) |

## 검토했으나 채택하지 않은 규칙

- **`interface-no-infrastructure`**(`interface/rest/` 라우터가 `infrastructure/`를 직접 import하면 실패): 이 저장소에서는 FastAPI에 DI 컨테이너가 없어 `interface/rest/*.py`의 `Depends` 팩토리 함수 자체가 "바인딩 지점"이다([module-pattern.md](../docs/architecture/module-pattern.md) "② `Depends` 팩토리 함수", [layer-architecture.md](../docs/architecture/layer-architecture.md) "Infrastructure 레이어" 참고) — 라우터가 `SqlAlchemyAccountRepository` 같은 구현체를 직접 import해 `Depends(_repo)` 팩토리를 구성하는 것이 문서가 명시하는 정상 패턴이다. 이 규칙을 그대로 적용하면 이 저장소의 모든 라우터가 실패해, 문서가 실제로 예시 코드로 보여주는 패턴과 정면으로 모순된다. 다른 언어(DI 컨테이너가 있는 NestJS/Spring 계열)에서는 유효할 수 있는 규칙이지만 FastAPI에는 적용하지 않는다.

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
