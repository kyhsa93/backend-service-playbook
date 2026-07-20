import { Injectable, Logger } from '@nestjs/common'

@Injectable()
export class DepositCommandHandler {
  private readonly logger = new Logger(DepositCommandHandler.name)

  public async execute(): Promise<void> {
    try {
      await this.doWork()
    } catch (error) {
      this.logger.error({ message: 'deposit failed', error })
      throw error
    }
  }

  private async doWork(): Promise<void> {}
}
