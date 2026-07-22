export class OrderCommandHandler {
  public async execute(): Promise<void> {
    throw new Error('Order not found.')
  }
}
