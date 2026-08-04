import { TaskConsumer } from '@/task-queue/task-consumer-decorator'

export class AccountTaskController {
  @TaskConsumer('account.cleanup-expired')
  public async handleCleanupExpired(): Promise<void> {}
}
