import { SendEmailCommand, SESClient } from '@aws-sdk/client-ses'
import { Inject, Injectable, Logger } from '@nestjs/common'

import { NotificationService } from '@/account/application/service/notification-service'
import { SentEmailEntity } from '@/account/infrastructure/notification/sent-email.entity'
import { SES_CLIENT } from '@/account/infrastructure/notification/ses-client-provider'
import { generateId } from '@/common/generate-id'
import { getSesSenderEmail } from '@/config/notification.config'
import { TransactionManager } from '@/database/transaction-manager'

@Injectable()
export class NotificationServiceImpl extends NotificationService {
  private readonly logger = new Logger(NotificationServiceImpl.name)

  constructor(
    @Inject(SES_CLIENT) private readonly sesClient: SESClient,
    private readonly transactionManager: TransactionManager
  ) {
    super()
  }

  public async sendEmail(params: {
    accountId: string
    eventType: string
    recipient: string
    subject: string
    body: string
  }): Promise<void> {
    const result = await this.sesClient.send(new SendEmailCommand({
      Source: getSesSenderEmail(),
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
