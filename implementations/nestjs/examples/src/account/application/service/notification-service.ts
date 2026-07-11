export abstract class NotificationService {
  abstract sendEmail(params: {
    accountId: string
    eventType: string
    recipient: string
    subject: string
    body: string
  }): Promise<void>
}
