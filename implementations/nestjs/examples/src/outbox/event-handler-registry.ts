import { Injectable, SetMetadata } from '@nestjs/common'

export const HANDLE_EVENT_METADATA = 'HANDLE_EVENT_METADATA'
export const HANDLE_INTEGRATION_EVENT_METADATA = 'HANDLE_INTEGRATION_EVENT_METADATA'

// Domain Event Handler(application/event/) 표식 데코레이터.
export function HandleEvent(eventType: string): MethodDecorator {
  return SetMetadata(HANDLE_EVENT_METADATA, eventType)
}

// 외부 BC Integration Event 수신부(interface/integration-event/) 표식 데코레이터.
export function HandleIntegrationEvent(eventName: string): MethodDecorator {
  return SetMetadata(HANDLE_INTEGRATION_EVENT_METADATA, eventName)
}

type EventHandlerFn = (payload: object) => Promise<void>

// eventType(문자열) → 핸들러 라우팅 레지스트리.
//
// 각 도메인 모듈(OnModuleInit)이 자기 도메인의 Domain Event Handler와 Integration
// Event 수신부를 register()로 이 레지스트리 하나에 등록한다. OutboxConsumer가 SQS에서
// 메시지를 받으면 eventType으로 이 레지스트리에서 핸들러를 찾아 호출한다 — Account/
// Payment가 각자 별도 OutboxRelay 고정 맵을 갖던 구조를 이 하나의 공유 레지스트리로
// 통합했다. 발행 BC(Account)가 수신 BC(Card)를 직접 import하지 않고도 Integration
// Event를 전달할 수 있게 하는 느슨한 연결 지점이기도 하다.
//
// (@HandleEvent·@HandleIntegrationEvent 데코레이터는 이 저장소에서 위치 표식일 뿐이며,
//  실제 라우팅은 각 모듈의 명시적 register() 호출로 구성한다.)
@Injectable()
export class EventHandlerRegistry {
  private readonly handlers = new Map<string, EventHandlerFn[]>()

  public register(eventType: string, handler: EventHandlerFn): void {
    const list = this.handlers.get(eventType) ?? []
    list.push(handler)
    this.handlers.set(eventType, list)
  }

  public has(eventType: string): boolean {
    return this.handlers.has(eventType)
  }

  public async handle(eventType: string, payload: object): Promise<void> {
    for (const handler of this.handlers.get(eventType) ?? []) {
      await handler(payload)
    }
  }
}
