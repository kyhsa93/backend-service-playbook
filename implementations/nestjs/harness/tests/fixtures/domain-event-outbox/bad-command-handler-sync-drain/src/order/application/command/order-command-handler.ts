import { OutboxPoller } from '@/outbox/outbox-poller'

// 잘못된 패턴 — Command Handler가 저장 직후 OutboxPoller를 직접 호출해 같은 프로세스
// 안에서 동기적으로 드레인을 시도한다. Poller/Consumer는 독립적으로 주기 실행되어야
// 하며 Command Handler가 이들을 참조해서는 안 된다.
export class OrderCommandHandler {
  constructor(private readonly poller: OutboxPoller) {}

  public async execute(): Promise<void> {
    await this.poller.poll()
  }
}
