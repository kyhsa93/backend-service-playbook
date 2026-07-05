# Backend Service Playbook

DDD 기반 백엔드 서비스의 **프레임워크 무관** 설계·구현 원칙을 담은 가이드이다.
특정 언어나 프레임워크에 의존하지 않는다. 코드 예시는 TypeScript를 사용하지만 패턴 자체는 어떤 언어에서도 동일하게 적용된다.

각 언어/프레임워크별 구현 상세는 `implementations/<lang>/` 참조.

## 작업 시 참조할 문서

### 설계 / 프로세스

| 작업 / 키워드 | 읽을 문서 |
|---|---|
| 개발 프로세스, 에이전트 역할, 설계 산출물, Vertical Slicing | `docs/development-process.md` |
| 작업 완료 후 자기 검토, 체크리스트 | `docs/checklist.md` |
| 디렉토리 구조, 파일 네이밍, 레이어별 파일 배치 | `docs/architecture/directory-structure.md` |

### 전략적 설계

| 작업 / 키워드 | 읽을 문서 |
|---|---|
| 전략적 설계, Subdomain, Bounded Context, Context Map, 유비쿼터스 언어 | `docs/architecture/strategic-ddd.md` |
| BC 간 통신 패턴 선택, 동기 vs 비동기, ACL | `docs/architecture/cross-domain-communication.md` |

### 전술적 설계

| 작업 / 키워드 | 읽을 문서 |
|---|---|
| Aggregate, Entity, Value Object, Domain Event, 경계 기준 | `docs/architecture/tactical-ddd.md` |
| Aggregate ID 생성, UUID, Domain 레이어 ID 할당 | `docs/architecture/aggregate-id.md` |
| 레이어 역할, Domain / Application / Interface / Infrastructure | `docs/architecture/layer-architecture.md` |
| Repository 인터페이스·구현, abstract class | `docs/architecture/repository-pattern.md` |
| Domain Service, 여러 Aggregate 조율, 도메인 판단 로직 | `docs/architecture/domain-service.md` |
| Domain Event, Outbox 패턴, Integration Event, 멱등성 | `docs/architecture/domain-events.md` |
| CQRS, Command/Query 분리, Handler 패턴 | `docs/architecture/cqrs-pattern.md` |

### 품질 / 인터페이스

| 작업 / 키워드 | 읽을 문서 |
|---|---|
| 에러 처리 원칙, 에러 메시지 타입화, 에러 응답 형식 | `docs/architecture/error-handling.md` |
| API 응답 구조, 페이지네이션, 목록/단건 응답 형식 | `docs/architecture/api-response.md` |
| 테스트 전략, Domain 단위 테스트, Application mock 테스트, E2E | `docs/architecture/testing.md` |
| 커밋 메시지, 브랜치 네이밍, REST API 설계, Rate Limiting | `docs/conventions.md` |

### 인증 / 횡단 관심사

| 작업 / 키워드 | 읽을 문서 |
|---|---|
| JWT 인증, Bearer 토큰, 토큰 발급/검증 흐름 | `docs/architecture/authentication.md` |
| 요청 파이프라인, Correlation ID, 횡단 관심사 처리 위치 | `docs/architecture/cross-cutting-concerns.md` |

### 비동기 / 스케줄링

| 작업 / 키워드 | 읽을 문서 |
|---|---|
| 스케줄링, Cron, Task Queue, 배치 작업, 다중 인스턴스 안전성 | `docs/architecture/scheduling.md` |
| Task Outbox 패턴, dual-write 차단, Task 멱등성 | `docs/architecture/scheduling.md` |

### 운영 / 인프라

| 작업 / 키워드 | 읽을 문서 |
|---|---|
| 로깅 레벨 정책, 구조화 로그, Correlation ID, 메트릭/트레이싱 | `docs/architecture/observability.md` |
| Graceful Shutdown, SIGTERM, Liveness/Readiness 프로브 | `docs/architecture/graceful-shutdown.md` |
| Dockerfile, 멀티스테이지 빌드, 컨테이너 이미지 원칙 | `docs/architecture/container.md` |
| 환경 변수 검증, Fail-fast, 설정 분리, Secrets Manager | `docs/architecture/config.md` |

### 구현체

| 프레임워크 | 문서 위치 |
|---|---|
| NestJS (TypeScript) | `implementations/nestjs/` — `CLAUDE.md` 참조 |
| Go | `implementations/go/docs/` |
| Spring Boot (Java) | `implementations/springboot/docs/` |
| FastAPI (Python) | `implementations/fastapi/docs/` |

## 구현 검증

작업 완료 후 하네스로 구조·배치 규칙 위반 여부를 확인한다. 설치 불필요.

```bash
./harness.sh <projectRoot>
```

체크리스트 기반 자기 검토는 `docs/checklist.md` 참조.
