export class OutboxRelay {
  private readonly handlers: Record<string, (payload: object) => Promise<void>> = {
    OrderCancelled: async (_payload: object) => {}
  }

  public async processPending(): Promise<void> {}
}
