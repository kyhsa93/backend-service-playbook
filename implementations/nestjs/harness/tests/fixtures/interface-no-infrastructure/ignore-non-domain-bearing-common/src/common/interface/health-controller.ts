import { ShutdownState } from '@/common/infrastructure/shutdown-state'

// common/은 domain/ 레이어가 없는 횡단 관심사 기술 모듈이라 이 규칙의 대상이
// 아니다(graceful-shutdown.md에 문서화된 의도적 패턴).
export class HealthController {
  constructor(private readonly shutdownState: ShutdownState) {}
}
