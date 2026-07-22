import { SendEmailCommand, SESClient } from '@aws-sdk/client-ses'
import { Inject, Injectable, Logger } from '@nestjs/common'

import { generateId } from '@/common/generate-id'
import { getSesSenderEmail } from '@/config/notification.config'
import { TransactionManager } from '@/database/transaction-manager'
import { CardStatementNotificationService } from '@/payment/application/service/card-statement-notification-service'
import { PAYMENT_SES_CLIENT } from '@/payment/infrastructure/notification/ses-client-provider'
import { SentCardStatementEntity } from '@/payment/infrastructure/notification/sent-card-statement.entity'

// Shaped like account/infrastructure/notification/notification-service-impl.ts — handles the
// SES send and the sent-record insert within a single method.
@Injectable()
export class CardStatementNotificationServiceImpl extends CardStatementNotificationService {
  private readonly logger = new Logger(CardStatementNotificationServiceImpl.name)

  constructor(
    @Inject(PAYMENT_SES_CLIENT) private readonly sesClient: SESClient,
    private readonly transactionManager: TransactionManager
  ) {
    super()
  }

  public async hasSentStatement(cardId: string, statementMonth: string): Promise<boolean> {
    const manager = this.transactionManager.getManager()
    const count = await manager.count(SentCardStatementEntity, { where: { cardId, statementMonth } })
    return count > 0
  }

  public async sendStatement(params: {
    cardId: string
    accountId: string
    statementMonth: string
    paymentCount: number
    totalAmount: number
    currency: string
    recipient: string
  }): Promise<void> {
    const subject = `[Card] ${params.statementMonth} 카드 사용내역 안내`
    const body = `${params.statementMonth} 카드(${params.cardId}) 사용내역입니다. `
      + `이용 건수: ${params.paymentCount}건, 총 이용금액: ${params.totalAmount} ${params.currency}`

    const result = await this.sesClient.send(new SendEmailCommand({
      Source: getSesSenderEmail(),
      Destination: { ToAddresses: [params.recipient] },
      Message: {
        Subject: { Data: subject, Charset: 'UTF-8' },
        Body: { Text: { Data: body, Charset: 'UTF-8' } }
      }
    }))

    const manager = this.transactionManager.getManager()
    await manager.insert(SentCardStatementEntity, {
      sentCardStatementId: generateId(),
      cardId: params.cardId,
      accountId: params.accountId,
      statementMonth: params.statementMonth,
      paymentCount: params.paymentCount,
      totalAmount: params.totalAmount,
      currency: params.currency,
      recipient: params.recipient,
      sesMessageId: result.MessageId ?? ''
    })

    this.logger.log({
      message: '카드 사용내역 발송됨',
      card_id: params.cardId,
      statement_month: params.statementMonth,
      recipient: params.recipient,
      ses_message_id: result.MessageId
    })
  }
}
