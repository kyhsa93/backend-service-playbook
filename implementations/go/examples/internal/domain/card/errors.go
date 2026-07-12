package card

import "errors"

var (
	ErrNotFound                       = errors.New("card not found")
	ErrLinkedAccountNotFound          = errors.New("linked account not found")
	ErrIssueRequiresActiveAccount     = errors.New("account must be active to issue a card")
	ErrCancelledCardCannotBeSuspended = errors.New("cancelled card cannot be suspended")
	ErrAlreadySuspended               = errors.New("card already suspended")
	ErrAlreadyCancelled               = errors.New("card already cancelled")
)
