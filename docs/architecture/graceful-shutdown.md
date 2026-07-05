# Graceful Shutdown

컨테이너 오케스트레이션 환경(Kubernetes, ECS 등)에서 SIGTERM 수신 시 진행 중인 요청을 안전하게 처리한 뒤 종료하는 패턴이다.

---

## 종료 흐름

```
1. 오케스트레이터가 SIGTERM 전송
2. readiness 프로브를 즉시 실패로 전환 → 로드밸런서가 새 트래픽 차단
3. 진행 중인 요청 처리 완료 대기
4. HTTP 서버 종료
5. DB 연결, 메시지 큐 연결 등 리소스 정리
6. 프로세스 정상 종료 (exit code 0)
```

**순서가 중요하다.** readiness 전환(2단계)이 HTTP 서버 종료(4단계)보다 먼저 이루어져야 새 요청이 종료 중인 인스턴스로 들어오지 않는다.

---

## Liveness vs Readiness 프로브

| 프로브 | 목적 | 실패 시 동작 | 종료 중 응답 |
|--------|------|------------|------------|
| **Liveness** `/health/live` | 프로세스가 살아있는지 | 컨테이너 재시작 | **200** (종료 중에도 살아있음) |
| **Readiness** `/health/ready` | 트래픽을 받을 준비가 됐는지 | 로드밸런서에서 제외 | **503** (새 트래픽 차단) |

**흔한 실수:** Liveness도 종료 중에 503을 반환하면 컨테이너가 종료 진행 중에 재시작된다. Liveness는 항상 200을 반환한다.

```
// 개념 — Readiness 상태 토글
isShuttingDown = false

// SIGTERM 수신 시
isShuttingDown = true

// GET /health/ready
if (isShuttingDown) return 503
return 200

// GET /health/live
return 200  // 항상
```

---

## terminationGracePeriodSeconds

오케스트레이터가 SIGTERM 이후 SIGKILL을 보내기까지 대기하는 시간이다.

```
SIGTERM → [terminationGracePeriodSeconds] → SIGKILL (강제 종료)
```

**설정 기준:** 서비스의 p99 요청 처리 시간보다 여유 있게 설정한다. 일반적으로 30초면 충분하다. 배치/스케줄 작업이 있으면 최대 처리 시간을 고려한다.

```yaml
# Kubernetes 예시
spec:
  terminationGracePeriodSeconds: 30
  containers:
    - livenessProbe:
        httpGet:
          path: /health/live
    - readinessProbe:
        httpGet:
          path: /health/ready
```

---

## 컨테이너에서 직접 프로세스 실행

```dockerfile
# 올바른 방식 — 프로세스를 PID 1로 직접 실행
CMD ["node", "dist/main.js"]

# 잘못된 방식 — npm이 중간에 끼어 SIGTERM 전달 지연
CMD ["npm", "run", "start:prod"]
```

npm/yarn을 래퍼로 쓰면 SIGTERM이 npm 프로세스에 전달되고 애플리케이션에는 늦게 전달되거나 전달되지 않을 수 있다. 직접 실행하면 애플리케이션이 PID 1을 가져 SIGTERM을 즉시 수신한다.

---

## 리소스 정리 원칙

종료 시 리소스 정리는 **HTTP 서버가 닫힌 후** 실행한다. 진행 중인 요청이 DB를 사용할 수 있어야 하기 때문이다.

```
✓ HTTP 서버 종료 → DB 연결 해제   (올바른 순서)
✗ DB 연결 해제 → HTTP 서버 종료   (진행 중인 요청이 DB를 쓸 수 없음)
```

정리 중 예외를 던지지 않는다. 정리 단계에서 예외가 발생하면 다른 리소스의 정리가 건너뛰어진다. try-catch로 감싸고 로그만 남긴다.

---

## 원칙

- **readiness 전환이 먼저**: SIGTERM 수신 즉시 readiness를 503으로 전환하여 새 트래픽을 차단한다.
- **liveness는 항상 200**: 종료 중에도 liveness는 200. 503으로 바꾸면 컨테이너가 재시작된다.
- **리소스 정리는 HTTP 서버 종료 후**: 진행 중인 요청이 DB를 사용할 수 있도록 순서를 지킨다.
- **직접 프로세스 실행**: npm/yarn 래퍼 없이 SIGTERM을 즉시 수신한다.
- **`terminationGracePeriodSeconds`는 p99 처리 시간보다 여유 있게**: 기본 30초.

---

### 관련 문서

- [container.md](container.md) — Dockerfile CMD 설정
- [observability.md](observability.md) — 헬스체크 로깅
