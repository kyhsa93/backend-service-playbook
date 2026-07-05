export class OrderCommandHandler {
  public async execute(): Promise<void> {
    throw new Error('주문을 찾을 수 없습니다.')
  }
}
