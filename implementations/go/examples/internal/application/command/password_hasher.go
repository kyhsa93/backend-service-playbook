package command

// PasswordHasher is a Technical Service port abstracting password
// hashing/verification (the Technical Service pattern from
// domain-service.md — like OutboxRelay/AccountAdapter, it is defined in the
// application layer in the minimal form the consumer needs, with the real
// implementation living in infrastructure). Concrete algorithms/libraries
// such as bcrypt are not exposed through this interface.
type PasswordHasher interface {
	Hash(plainPassword string) (string, error)
	Verify(plainPassword, passwordHash string) (bool, error)
}
