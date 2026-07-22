package outbox

// A fixture that deliberately declares no Writer/Poller/Consumer types — verifies
// that the shared-infra rule doesn't pass just because the outbox/ directory
// exists, but checks for the actual type declarations.
type SomethingElse struct{}
