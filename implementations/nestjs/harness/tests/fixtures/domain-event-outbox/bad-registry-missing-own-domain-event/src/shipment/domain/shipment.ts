export class ShipmentDispatched {
  constructor(public readonly shipmentId: string, public readonly carrier: string) {}
}

export class Shipment {
  private readonly _events: ShipmentDispatched[] = []

  constructor(public readonly shipmentId: string, private _status: string) {}

  public get domainEvents(): ShipmentDispatched[] { return [...this._events] }

  public dispatch(carrier: string): void {
    this._status = 'dispatched'
    this._events.push(new ShipmentDispatched(this.shipmentId, carrier))
  }

  public clearEvents(): void { this._events.length = 0 }
}
