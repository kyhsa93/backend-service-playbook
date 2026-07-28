package outbox

import (
	"context"
	"errors"
	"testing"
)

// These tests exercise runHandlers directly rather than the full Consumer
// (whose sqs field is a concrete *sqs.Client with no lightweight fake
// available) — it is the piece of dispatch logic responsible for the 1:N
// "every eventType may have more than one handler" contract this package
// documents (see Consumer's doc comment in consumer.go), so it's the
// meaningful unit to verify in isolation.
func TestRunHandlers_MultipleHandlersForTheSameEventType_CallsAllOfThem(t *testing.T) {
	var firstCalled, secondCalled bool
	handlers := []Handler{
		func(ctx context.Context, payload []byte) error { firstCalled = true; return nil },
		func(ctx context.Context, payload []byte) error { secondCalled = true; return nil },
	}

	if err := runHandlers(context.Background(), "SomeEvent", handlers, []byte(`{}`)); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if !firstCalled || !secondCalled {
		t.Fatalf("want both handlers called, got first=%v second=%v", firstCalled, secondCalled)
	}
}

func TestRunHandlers_OneHandlerFails_StillCallsTheOtherHandlerRatherThanStoppingEarly(t *testing.T) {
	var secondCalled bool
	handlers := []Handler{
		func(ctx context.Context, payload []byte) error { return errors.New("boom") },
		func(ctx context.Context, payload []byte) error { secondCalled = true; return nil },
	}

	err := runHandlers(context.Background(), "SomeEvent", handlers, []byte(`{}`))

	if err == nil {
		t.Fatal("want the failure to be returned so the caller leaves the message unacked")
	}
	if !secondCalled {
		t.Fatal("want the second handler to still run despite the first one failing")
	}
}

func TestRunHandlers_AHandlerFails_ReturnsTheErrorSoTheCallerCanLeaveTheMessageUnacked(t *testing.T) {
	handlers := []Handler{
		func(ctx context.Context, payload []byte) error { return errors.New("boom") },
	}

	if err := runHandlers(context.Background(), "SomeEvent", handlers, []byte(`{}`)); err == nil {
		t.Fatal("want an error, got nil")
	}
}

func TestRunHandlers_NoHandlers_ReturnsNil(t *testing.T) {
	if err := runHandlers(context.Background(), "UnregisteredEvent", nil, []byte(`{}`)); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
}
