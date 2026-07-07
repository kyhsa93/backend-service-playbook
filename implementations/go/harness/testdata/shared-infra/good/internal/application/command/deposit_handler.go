package command

type OutboxRelay interface {
	ProcessPending() error
}
