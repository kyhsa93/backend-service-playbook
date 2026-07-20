package account

import "errors"

type Account struct {
	Status string
}

// errors.go가 아닌 이 파일에서 ad-hoc sentinel을 선언하는 위반 사례.
func (a *Account) Deposit(amount int64) error {
	if amount <= 0 {
		return errors.New("amount must be greater than zero")
	}
	return nil
}
