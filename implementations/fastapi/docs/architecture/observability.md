# Observability — 로깅, 메트릭, 트레이싱

> 프레임워크 무관 원칙: [../../../../docs/architecture/observability.md](../../../../docs/architecture/observability.md)

## 구조화 로깅과 Correlation ID

`src/common/logging_config.py`의 `JsonFormatter`/`configure_logging()`, `src/common/correlation.py`의 `contextvars` 기반 Correlation ID, `main.py`의 `correlation_id_middleware`가 모두 실제 코드로 존재한다. `infrastructure/notification/notification_service.py`는 `extra={...}`로 구조화된 필드(`event_type`, `account_id`, `recipient`, `ses_message_id`)를 전달한다.

```python
# infrastructure/notification/notification_service.py — 실제 코드
logger.info(
    "알림 이메일 발송됨",
    extra={
        "event_type": event_type,
        "account_id": event.account_id,
        "recipient": recipient,
        "ses_message_id": ses_message_id,
    },
)
```

아래는 이 실제 구현의 상세다.

---

## 구조화 로깅 선택: stdlib `logging` + JSON 포매터

`structlog`는 강력하지만 별도 의존성과 학습 비용이 추가된다. 이 프로젝트는 이미 표준 `logging` 모듈(`getLogger(__name__)`)을 쓰고 있고 로그 양이 많지 않으므로, **stdlib `logging`에 커스텀 JSON `Formatter`를 얹는 방식**을 권장한다 — 새 의존성 없이 필드명 규칙(snake_case)과 Correlation ID만 강제하면 충분하다. 로그량이 크게 늘거나 컨텍스트 바인딩(`logger.bind(...)`)이 필요해지면 그때 `structlog`로 전환을 검토한다.

```python
# src/common/logging_config.py — 실제 코드
import json
import logging

from .correlation import get_correlation_id

_BASE_RECORD_KEYS = set(logging.LogRecord("", 0, "", 0, "", (), None).__dict__)


class JsonFormatter(logging.Formatter):
    def format(self, record: logging.LogRecord) -> str:
        payload = {
            "level": record.levelname.lower(),
            "message": record.getMessage(),
            "logger": record.name,
            "correlation_id": get_correlation_id(),
        }
        # extra={...}로 전달된 필드를 병합한다 (예: account_id, duration_ms)
        for key, value in record.__dict__.items():
            if key not in _BASE_RECORD_KEYS and key != "message":
                payload[key] = value
        if record.exc_info:
            payload["exc_info"] = self.formatException(record.exc_info)
        return json.dumps(payload, default=str)


def configure_logging(level: str = "INFO") -> None:
    handler = logging.StreamHandler()
    handler.setFormatter(JsonFormatter())
    logging.basicConfig(level=level, handlers=[handler])
```

```python
# main.py — lifespan 진입 전에 호출
from src.common.logging_config import configure_logging

configure_logging()
```

### 필드 네이밍 — snake_case, `extra=`로 구조화 필드 전달

```python
# infrastructure/notification/notification_service.py — 실제 코드
logger.info(
    "알림 이메일 발송됨",
    extra={
        "event_type": event_type,
        "account_id": event.account_id,
        "recipient": recipient,
        "ses_message_id": ses_message_id,
    },
)
```

`%s` 문자열 보간 대신 `extra=`에 구조화된 필드를 담으면, `JsonFormatter`가 이를 최상위 JSON 키로 병합한다 — Datadog/CloudWatch가 `account_id`로 바로 필터링할 수 있다.

---

## Correlation ID — `contextvars`

Node의 `AsyncLocalStorage`에 대응하는 Python 표준 라이브러리가 `contextvars`다. 구현과 미들웨어 연동은 [cross-cutting-concerns.md](cross-cutting-concerns.md)에서 다룬다 — 이 문서는 로깅에서의 사용만 요약한다.

```python
# src/common/correlation.py
from contextvars import ContextVar

_correlation_id: ContextVar[str | None] = ContextVar("correlation_id", default=None)


def get_correlation_id() -> str:
    return _correlation_id.get() or "unknown"
```

`JsonFormatter`가 매 로그 라인마다 `get_correlation_id()`를 호출하므로, 미들웨어가 요청 진입 시 설정한 Correlation ID가 그 요청 처리 중 발생하는 모든 로그(라우터, Handler, `SesNotificationService.notify()`)에 자동으로 포함된다 — 호출마다 `correlation_id`를 인자로 넘길 필요가 없다.

---

## 로그 레벨 정책

| 레벨 | 용도 | 이 저장소의 예 |
|------|------|----------------|
| `ERROR` | 요청 처리 실패, 외부 시스템 장애 | `SesNotificationService.notify()`의 `logger.exception(...)` |
| `WARNING` | 정상 동작이지만 주의 필요 | (현재 사용처 없음) — 예: 재시도 발생 |
| `INFO` | 주요 비즈니스 이벤트, 기동/종료 | 알림 발송 성공, `lifespan` 기동/종료 |
| `DEBUG` | 개발/디버깅 상세 정보 | SQLAlchemy 쿼리 파라미터 |

프로덕션에서는 `configure_logging(level="INFO")`로 `DEBUG`를 끈다. 개발 환경에서는 `DEBUG`까지 출력한다 — [config.md](config.md)의 환경별 설정과 연동한다.

---

## 레이어별 로깅 기준

| 레이어 | 로깅 대상 |
|--------|----------|
| Interface (`account_router.py`, 미들웨어) | HTTP 요청/응답, 처리 시간 |
| Application (`*_handler.py`) | 비즈니스 이벤트 (필요 시) |
| Infrastructure (`notification_service.py`) | 외부 연동 실패/성공, SQL 성능 이상 |
| Domain (`account.py`) | **로깅하지 않음** |

`src/account/domain/account.py`는 어떤 로거도 import하지 않는다 — Domain 순수성이 유지된다. `domain/`에서 `logging`/`structlog` import 여부는 harness의 `domain-purity` 규칙(fastapi/sqlalchemy/aioboto3와 함께 로깅 라이브러리도 블록리스트에 포함)이 검사한다.

---

## 메트릭 · 트레이싱 (방향 메모)

이 저장소는 특정 스택을 강제하지 않지만, 프로덕션 도입 시 다음을 권장한다.

- **메트릭**: `prometheus-fastapi-instrumentator`로 `GET /metrics` 자동 노출. 핵심 알람: HTTP 5xx rate, p99 응답 시간, DB 커넥션 풀 포화.
- **트레이싱**: `opentelemetry-instrumentation-fastapi` + `opentelemetry-instrumentation-sqlalchemy`로 HTTP/DB span 자동 수집. 로그 레코드에 `trace_id`를 포함하면 trace ↔ log 점프가 가능해진다.

---

## 원칙

- **Domain 레이어에서 로깅 금지**.
- **구조화된 로그 사용**: JSON + snake_case 필드명. `%s` 문자열 보간 대신 `extra=` 사용.
- **에러는 반드시 로깅 후 전파**: `logger.exception()` 뒤 예외를 삼키는 경우(`notify()`)는 [domain-events.md](domain-events.md)의 Outbox로 보완한다. `application/`·`infrastructure/`의 `except ...: pass`(로깅도 재전파도 없이 조용히 삼키는 패턴)는 harness의 `no-silent-except` 규칙이 잡는다.
- **Correlation ID로 요청 추적**: `contextvars` 기반, 모든 로그 라인에 자동 포함.
- **프로덕션에서 DEBUG 비활성화**: 환경별 로그 레벨 설정.

---

### 관련 문서

- [cross-cutting-concerns.md](cross-cutting-concerns.md) — Correlation ID 주입 미들웨어
- [layer-architecture.md](layer-architecture.md) — 레이어별 역할 분리
- [config.md](config.md) — 환경별 로그 레벨 설정
