// A fixture that intentionally does not have OutboxWriter.kt/OutboxPoller.kt/OutboxConsumer.kt —
// verifies that the shared-infra rule checks for the actual files' existence rather than passing on
// the mere existence of the outbox/ directory.
class SomethingElse
