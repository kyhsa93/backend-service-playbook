class EventHandlerRegistry {
  register(_eventType: string, _handler: (payload: object) => Promise<void>): void {}
}

export class ShipmentModule {
  constructor(private readonly registry: EventHandlerRegistry) {}

  onModuleInit(): void {
    this.registry.register('ShipmentDispatched', async () => {})
  }
}
