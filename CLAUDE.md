# Backend Service Playbook

DDD 기반 백엔드 서비스의 **프레임워크 무관** 설계·구현 원칙을 담은 가이드이다.
특정 언어나 프레임워크에 의존하지 않는다. 코드 예시는 TypeScript를 사용하지만 패턴 자체는 어떤 언어에서도 동일하게 적용된다.

각 언어/프레임워크별 구현 상세는 `docs/implementations/` 참조.

## 작업 시 참조할 문서

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
| 커밋 메시지, 브랜치 네이밍, REST API 설계 원칙 | `docs/conventions.md` |

### 구현체

| 프레임워크 | 읽을 문서 |
|---|---|
| NestJS (TypeScript) | `docs/implementations/nestjs.md` |
