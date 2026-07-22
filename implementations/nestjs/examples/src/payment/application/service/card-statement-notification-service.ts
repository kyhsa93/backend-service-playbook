// A Technical Service — an SES email-sending abstraction shaped like
// account/application/service/notification-service.ts. It's kept Payment BC-only because of
// the "a Technical Service is scoped to its domain (YAGNI)" principle — rather than reusing
// Account's NotificationService as-is (a cross-BC export), Payment has its own minimal
// contract for what it needs (see docs/architecture/domain-service.md).
//
// sendStatement binds "sending" and "recording idempotency" into a single call — the same
// pattern as Account's NotificationServiceImpl recording a SentEmailEntity within the same
// method right after the SES send. hasSentStatement() queries that record in a Level 1 style
// to prevent duplicate sends per card×month for the payment.send-card-statements Task
// (see send-card-statements-command-handler.ts).
export abstract class CardStatementNotificationService {
  abstract hasSentStatement(cardId: string, statementMonth: string): Promise<boolean>

  abstract sendStatement(params: {
    cardId: string
    accountId: string
    statementMonth: string
    paymentCount: number
    totalAmount: number
    currency: string
    recipient: string
  }): Promise<void>
}
