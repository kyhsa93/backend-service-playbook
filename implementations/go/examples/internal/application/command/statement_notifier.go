package command

import "context"

// StatementNotifier is the minimal port SendCardUsageStatementHandler needs
// to send card usage statement emails. It serves the same purpose as
// application/event.Notifier (account Domain Event -> email: SES delivery +
// delivery record), but its signature is composed only of primitive types
// so that the Card BC's Application layer does not depend on the
// account.DomainEvent type (importing the account package would violate the
// cross-BC-application-import rule). The implementation is handled by
// infrastructure/notification.Service, reusing the existing SES delivery path.
type StatementNotifier interface {
	NotifyCardStatement(ctx context.Context, accountID, recipient, cardID, period string, paymentCount int, totalAmount int64) error
}
