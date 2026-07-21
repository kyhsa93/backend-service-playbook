// Task Controller 메서드에 taskType을 바인딩하는 데코레이터. outbox/event-handler-registry.ts의
// @HandleEvent와 같은 목적(위치 표식 + 런타임 조회)이지만, Domain Event는 1:N fan-out이라
// 명시적 register() 호출로 라우팅하는 반면 Task는 taskType당 정확히 1개의 핸들러만 허용되는
// 1:1 라우팅이라 전역 유일성 검증이 필요하다 — 그래서 등록 시점(모듈 로딩 시 이 파일이
// import되어 클래스 본문이 평가될 때)에 즉시 검증하는 데코레이터 + 전역 Map 조합을 쓴다
// (docs/architecture/scheduling.md#taskconsumer-데코레이터).
const TASK_HANDLER_MAP = new Map<string, { handlerClass: new (...args: unknown[]) => unknown; method: string }>()

export function TaskConsumer(taskType: string): MethodDecorator {
  return (target, propertyKey) => {
    if (TASK_HANDLER_MAP.has(taskType)) {
      throw new Error(`Duplicate @TaskConsumer for taskType: ${taskType}`)
    }
    TASK_HANDLER_MAP.set(taskType, {
      handlerClass: target.constructor as new (...args: unknown[]) => unknown,
      method: propertyKey as string
    })
  }
}

export function getTaskHandler(taskType: string): { handlerClass: new (...args: unknown[]) => unknown; method: string } | undefined {
  return TASK_HANDLER_MAP.get(taskType)
}
