package event

import "context"

// IntegrationPublisher is the port an Application EventHandler uses to
// translate an internal Domain Event into an Integration Event for external
// BCs and persist it to the Outbox. The real implementation is handled by
// outbox.Publisher (inserting a separate Outbox row). Only the minimal
// signature needed here is declared.
//
// The EventHandler in application/event/ is the only point that can write
// directly to the Outbox through this port — an Aggregate never creates an
// Integration Event itself; the translation point is always the EventHandler.
type IntegrationPublisher interface {
	Publish(ctx context.Context, eventName string, payload any) error
}
