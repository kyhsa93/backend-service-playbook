# Backend Service Playbook

DDD 기반 백엔드 서비스의 **프레임워크·언어 무관** 설계·구현 원칙을 담은 가이드.

코드 예시는 TypeScript를 사용하지만, 패턴 자체는 Go·Java·Python 등 어떤 언어에도 동일하게 적용된다.
언어·프레임워크별 구현 상세는 `docs/implementations/` 참조.

---

## 구성

```
docs/
  architecture/     핵심 설계 원칙 (DDD, 레이어, Repository, CQRS 등)
  checklist.md      구현 완료 후 자기 검토 체크리스트
  conventions.md    커밋·브랜치·REST API 컨벤션
  development-process.md  설계→구현 워크플로우 (8 에이전트 역할)
  implementations/  언어·프레임워크별 구현 가이드

harness.sh          구조·파일 배치 검증 스크립트
CLAUDE.md           AI 에이전트용 문서 인덱스 (키워드 → 문서)
AGENTS.md           AI 에이전트 작업 가이드 (워크플로우·원칙)
```

---

## 하네스 사용법

설치 불필요. bash/zsh가 있으면 바로 실행된다.

```bash
./harness.sh <projectRoot>
```

검사 항목:

| 항목 | 설명 |
|------|------|
| structure | 도메인별 4레이어 디렉토리 존재 여부 |
| cqrs-pattern | application/command/, application/query/ 분리 |
| file-placement | 파일명 suffix 기반 레이어 배치 규칙 |
| shared-infra | outbox·task-queue 패턴 사용 시 공용 모듈 존재 여부 |
| event-placement | 이벤트 핸들러·인티그레이션 이벤트 배치 |

---

## 관련 레포

- [nestjs-playbook](../nestjs-playbook) — NestJS(TypeScript) 구현 가이드 + 하네스
