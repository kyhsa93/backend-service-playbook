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
    // Confirm the card exists and is active via the synchronous Adapter (ACL) — a synchronous call since it's needed for the response (whether payment is allowed).
    const card = await this.cardAdapter.findCard({ cardId: command.cardId, ownerId: command.requesterId })
    if (!card) throw new Error(ErrorMessage['The card to link could not be found.'])
    if (!card.active) throw new Error(ErrorMessage['Only an active card can be used for payment.'])

    // Confirm via the synchronous Adapter (ACL) that the linked account is active and has a
    // sufficient balance (a read-only judgment). The actual debit isn't done here — Account BC
    // subscribes to PaymentCompleted → payment.completed.v1 and performs it asynchronously (the
    // root's "synchronous = query, asynchronous = state change" principle).
    const account = await this.accountAdapter.findAccount({ accountId: card.accountId, ownerId: command.requesterId })
    if (!account) throw new Error(ErrorMessage['The linked account could not be found.'])
    if (!account.active) throw new Error(ErrorMessage['Only an active account can be used for payment.'])
    if (account.balanceAmount < command.amount) throw new Error(ErrorMessage['Payment cannot be made due to insufficient account balance.'])

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
