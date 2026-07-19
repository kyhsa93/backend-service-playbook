import { CommandHandler, ICommandHandler } from '@nestjs/cqrs'

import { TransactionManager } from '@/database/transaction-manager'
import { AccountAdapter } from '@/payment/application/adapter/account-adapter'
import { CardAdapter } from '@/payment/application/adapter/card-adapter'
import { CreatePaymentCommand } from '@/payment/application/command/create-payment-command'
import { Payment } from '@/payment/domain/payment'
import { PaymentRepository } from '@/payment/domain/payment-repository'
import { PaymentErrorMessage as ErrorMessage } from '@/payment/payment-error-message'

@CommandHandler(CreatePaymentCommand)
export class CreatePaymentCommandHandler implements ICommandHandler<CreatePaymentCommand, Payment> {
  constructor(
    private readonly paymentRepository: PaymentRepository,
    private readonly cardAdapter: CardAdapter,
    private readonly accountAdapter: AccountAdapter,
    private readonly transactionManager: TransactionManager
  ) {}

  public async execute(command: CreatePaymentCommand): Promise<Payment> {
    // 동기 Adapter(ACL)로 카드가 존재·활성 상태인지 확인 — 응답(결제 가부)에 필요하므로 동기 호출.
    const card = await this.cardAdapter.findCard({ cardId: command.cardId, ownerId: command.requesterId })
    if (!card) throw new Error(ErrorMessage['연결할 카드를 찾을 수 없습니다.'])
    if (!card.active) throw new Error(ErrorMessage['활성 상태의 카드로만 결제할 수 있습니다.'])

    // 동기 Adapter(ACL)로 연결 계좌가 활성이고 잔액이 충분한지 확인(읽기 전용 판단).
    // 실제 차감은 여기서 하지 않는다 — PaymentCompleted → payment.completed.v1을
    // Account BC가 구독해 비동기로 수행한다(root의 "동기=조회, 비동기=상태변경" 원칙).
    const account = await this.accountAdapter.findAccount({ accountId: card.accountId, ownerId: command.requesterId })
    if (!account) throw new Error(ErrorMessage['연결된 계좌를 찾을 수 없습니다.'])
    if (!account.active) throw new Error(ErrorMessage['활성 상태의 계좌로만 결제할 수 있습니다.'])
    if (account.balanceAmount < command.amount) throw new Error(ErrorMessage['계좌 잔액이 부족하여 결제할 수 없습니다.'])

    const payment = Payment.create({
      cardId: command.cardId,
      accountId: card.accountId,
      ownerId: command.requesterId,
      amount: command.amount
    })
    payment.complete()

    await this.transactionManager.run(async () => {
      await this.paymentRepository.savePayment(payment)
    })
    return payment
  }
}
