export class DepositCommandHandler {
  public async execute(): Promise<void> {
    try {
      await this.doWork()
    } catch (error) {
      const ignored = error
    }
  }

  private async doWork(): Promise<void> {}
}
