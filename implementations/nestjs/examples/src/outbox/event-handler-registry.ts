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
// 각 BC가 자기 수신부를 register()로 등록하고, OutboxRelay가 outbox를 드레인할 때
// 자신의 정적 핸들러 맵에 없는 eventType(주로 다른 BC가 발행한 Integration Event)을
// 이 레지스트리로 위임한다. 발행 BC(Account)가 수신 BC(Card)를 직접 import하지 않고도
// in-process로 Integration Event를 전달할 수 있게 하는 느슨한 연결 지점이다.
//
// (@HandleEvent·@HandleIntegrationEvent 데코레이터는 이 저장소에서 위치 표식일 뿐이며,
//  실제 라우팅은 relay의 정적 맵과 이 레지스트리의 명시적 register()로 구성한다.)
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
