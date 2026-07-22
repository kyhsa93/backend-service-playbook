import { OutboxPoller } from '@/outbox/outbox-poller'

// An incorrect pattern — the Command Handler calls OutboxPoller directly right after saving,
// attempting to drain synchronously within the same process. The Poller/Consumer must run
// independently on their own schedule, and the Command Handler must never reference them.
export class OrderCommandHandler {
  constructor(private readonly poller: OutboxPoller) {}

  public async execute(): Promise<void> {
    await this.poller.poll()
  }
}
