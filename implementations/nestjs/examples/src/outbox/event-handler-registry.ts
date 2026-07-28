import { Injectable, Logger, SetMetadata } from '@nestjs/common'

export const HANDLE_EVENT_METADATA = 'HANDLE_EVENT_METADATA'
export const HANDLE_INTEGRATION_EVENT_METADATA = 'HANDLE_INTEGRATION_EVENT_METADATA'

// A marker decorator for a Domain Event Handler (application/event/).
export function HandleEvent(eventType: string): MethodDecorator {
  return SetMetadata(HANDLE_EVENT_METADATA, eventType)
}

// A marker decorator for an external-BC Integration Event receiving end (interface/integration-event/).
export function HandleIntegrationEvent(eventName: string): MethodDecorator {
  return SetMetadata(HANDLE_INTEGRATION_EVENT_METADATA, eventName)
}

type EventHandlerFn = (payload: object) => Promise<void>

// A registry routing eventType (a string) → a handler.
//
// Each domain module (OnModuleInit) registers its own domain's Domain Event Handlers and
// Integration Event receiving ends into this single registry via register(). When
// OutboxConsumer receives a message from SQS, it looks up and calls the handler in this
// registry by eventType — this unifies the structure where Account/Payment each had their own
// fixed OutboxRelay map into this one shared registry. It's also the loose coupling point that
// lets the publishing BC (Account) deliver an Integration Event without directly importing the
// receiving BC (Card).
//
// (The @HandleEvent·@HandleIntegrationEvent decorators are just positional markers in this
//  repo — the actual routing is configured via each module's explicit register() call.)
@Injectable()
export class EventHandlerRegistry {
  private readonly logger = new Logger(EventHandlerRegistry.name)
  private readonly handlers = new Map<string, EventHandlerFn[]>()

  public register(eventType: string, handler: EventHandlerFn): void {
    const list = this.handlers.get(eventType) ?? []
    list.push(handler)
    this.handlers.set(eventType, list)
  }

  public has(eventType: string): boolean {
    return this.handlers.has(eventType)
  }

  // Each handler is independent — one subscriber's failure must not prevent a sibling
  // subscriber on the same eventType from running (the "1:N" contract this class documents).
  // Every handler still gets a chance to run on every delivery, but the message is left
  // unacked (rethrown) if any of them failed, so OutboxConsumer redelivers it — each handler
  // must already be idempotent for that redelivery (see docs/architecture/domain-events.md).
  public async handle(eventType: string, payload: object): Promise<void> {
    const errors: unknown[] = []
    for (const handler of this.handlers.get(eventType) ?? []) {
      try {
        await handler(payload)
      } catch (error) {
        this.logger.error({ message: 'A handler failed for eventType', event_type: eventType, error })
        errors.push(error)
      }
    }
    if (errors.length > 0) throw errors[0]
  }
}
