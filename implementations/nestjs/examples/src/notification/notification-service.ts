import { SendEmailCommand, SESClient } from '@aws-sdk/client-ses'
import { Inject, Injectable, Logger } from '@nestjs/common'

import { generateId } from '@/common/generate-id'
import { TransactionManager } from '@/database/transaction-manager'
import { SentEmailEntity } from '@/notification/sent-email.entity'
import { SES_CLIENT } from '@/notification/ses-client-provider'

const DEFAULT_SENDER = 'no-reply@backend-service-playbook.example.com'

@Injectable()
export class NotificationService {
  private readonly logger = new Logger(NotificationService.name)

  constructor(
    @Inject(SES_CLIENT) private readonly sesClient: SESClient,
    private readonly transactionManager: TransactionManager
  ) {}

  public async sendEmail(params: {
    accountId: string
    eventType: string
    recipient: string
    subject: string
    body: string
  }): Promise<void> {
    const result = await this.sesClient.send(new SendEmailCommand({
      Source: process.env.SES_SENDER_EMAIL ?? DEFAULT_SENDER,
      Destination: { ToAddresses: [params.recipient] },
      Message: {
        Subject: { Data: params.subject, Charset: 'UTF-8' },
        Body: { Text: { Data: params.body, Charset: 'UTF-8' } }
      }
    }))

    const manager = this.transactionManager.getManager()
    await manager.insert(SentEmailEntity, {
      sentEmailId: generateId(),
      accountId: params.accountId,
      eventType: params.eventType,
      recipient: params.recipient,
      subject: params.subject,
      sesMessageId: result.MessageId ?? ''
    })

    this.logger.log({
      message: '이메일 발송됨',
      account_id: params.accountId,
      event_type: params.eventType,
      recipient: params.recipient,
      ses_message_id: result.MessageId
    })
  }
}
