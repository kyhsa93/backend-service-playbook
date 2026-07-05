import { SetMetadata } from '@nestjs/common'

export const HANDLE_EVENT_METADATA = 'HANDLE_EVENT_METADATA'

export function HandleEvent(eventType: string): MethodDecorator {
  return SetMetadata(HANDLE_EVENT_METADATA, eventType)
}
