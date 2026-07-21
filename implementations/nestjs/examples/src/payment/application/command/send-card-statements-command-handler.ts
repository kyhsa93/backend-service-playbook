import { CommandHandler, ICommandHandler } from '@nestjs/cqrs'

import { AccountAdapter } from '@/payment/application/adapter/account-adapter'
import { CardAdapter } from '@/payment/application/adapter/card-adapter'
import { SendCardStatementsCommand } from '@/payment/application/command/send-card-statements-command'
import { CardStatementNotificationService } from '@/payment/application/service/card-statement-notification-service'
import { PaymentRepository } from '@/payment/domain/payment-repository'
import { PaymentStatus } from '@/payment/payment-enum'

// payment.send-card-statements Task Controller가 위임하는 Command다.
//
// 이 유스케이스가 Payment BC에 있는 이유: "카드 사용내역"이지만 원본 데이터(결제 건수·
// 총액)는 Payment Aggregate가 소유한다. Card BC는 이미 Payment BC의 의존 대상이라
// (PaymentModule이 CardModule을 import) 반대 방향(Card → Payment)으로 조회 Adapter를
// 추가하면 순환 의존이 생긴다. Payment는 이미 CardAdapter/AccountAdapter(ACL)로 두
// BC를 모두 동기 조회할 수 있으므로, 여기서 조율하는 것이 순환 없이 가능한 유일한
// 배치다(card/card-module.ts, payment/payment-module.ts의 imports 방향 참고).
//
// 멱등성은 Level 1에 가깝다 — sendStatement() 직전에 hasSentStatement()로 같은
// (cardId, statementMonth) 조합이 이미 있는지 확인하고 스킵한다. 최종 방어선은
// sent_card_statement의 (cardId, statementMonth) 유니크 제약이다.
@CommandHandler(SendCardStatementsCommand)
export class SendCardStatementsCommandHandler implements ICommandHandler<SendCardStatementsCommand, number> {
  constructor(
    private readonly cardAdapter: CardAdapter,
    private readonly accountAdapter: AccountAdapter,
    private readonly paymentRepository: PaymentRepository,
    private readonly notificationService: CardStatementNotificationService
  ) {}

  public async execute(command: SendCardStatementsCommand): Promise<number> {
    const activeCards = await this.cardAdapter.findActiveCards()
    let sentCount = 0

    for (const card of activeCards) {
      const alreadySent = await this.notificationService.hasSentStatement(card.cardId, command.statementMonth)
      if (alreadySent) continue

      const summary = await this.paymentRepository.summarizePayments({
        cardId: card.cardId,
        status: [PaymentStatus.COMPLETED],
        createdAtFrom: command.monthStart,
        createdAtTo: command.monthEnd
      })

      // 계좌를 찾지 못하면(이미 삭제/이관 등 방어적 케이스) 발송 대상이 없으므로 스킵한다.
      const account = await this.accountAdapter.findAccount({ accountId: card.accountId, ownerId: card.ownerId })
      if (!account) continue

      // 실제 카드 명세서처럼 이용 건수가 0이어도 발송한다 — 이자 지급(interest)과 달리
      // "이번 달 사용내역"은 활동이 없었다는 사실 자체가 유의미한 정보다.
      await this.notificationService.sendStatement({
        cardId: card.cardId,
        accountId: card.accountId,
        statementMonth: command.statementMonth,
        paymentCount: summary.count,
        totalAmount: summary.totalAmount,
        currency: account.currency,
        recipient: account.email
      })
      sentCount++
    }

    return sentCount
  }
}
