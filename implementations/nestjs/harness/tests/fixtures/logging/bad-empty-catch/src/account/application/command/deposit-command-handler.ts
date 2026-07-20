export class DepositCommandHandler {
  public async execute(): Promise<void> {
    try {
      await this.doWork()
    } catch (error) {
    }
  }

  private async doWork(): Promise<void> {}
}
