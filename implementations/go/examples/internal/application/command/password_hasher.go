package command

// PasswordHasher는 비밀번호 해싱/검증을 추상화하는 Technical Service 포트다
// (domain-service.md의 Technical Service 패턴 — OutboxRelay/AccountAdapter와 동일하게
// 사용하는 쪽이 필요로 하는 최소 형태로 application 레이어에 정의하고, 실제 구현은
// infrastructure에 둔다). bcrypt 같은 구체 알고리즘/라이브러리는 이 인터페이스에
// 드러나지 않는다.
type PasswordHasher interface {
	Hash(plainPassword string) (string, error)
	Verify(plainPassword, passwordHash string) (bool, error)
}
