function HandleEvent(_eventType: string): MethodDecorator {
  return () => undefined
}

export class ShipmentDispatchedHandler {
  @HandleEvent('ShipmentDispatched')
  public async handle(_event: { shipmentId: string; carrier: string }): Promise<void> {}
}
