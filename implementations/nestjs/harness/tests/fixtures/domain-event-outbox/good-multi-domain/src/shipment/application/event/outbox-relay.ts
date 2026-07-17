export class OutboxRelay {
  private readonly handlers: Record<string, (payload: object) => Promise<void>> = {
    ShipmentDispatched: async (_payload: object) => {}
  }

  public async processPending(): Promise<void> {}
}
