export class OutboxRelay {
  private readonly handlers: Record<string, (payload: object) => Promise<void>> = {}

  public async processPending(): Promise<void> {}
}
