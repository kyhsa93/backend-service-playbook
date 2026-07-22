package command

// TokenIssuer is the port that issues an Access Token after successful
// authentication. The real implementation (JWT) lives in
// internal/infrastructure/auth — since *auth.JWTService already satisfies
// this signature (Sign(userID string) (string, error)) as-is, Go's
// structural typing lets it be injected directly with no separate adapter
// type needed.
type TokenIssuer interface {
	Sign(userID string) (string, error)
}
