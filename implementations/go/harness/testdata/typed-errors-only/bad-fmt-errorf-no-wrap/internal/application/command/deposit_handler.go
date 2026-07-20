package command

import "fmt"

// %w로 기존 typed error를 감싸지 않는 free-form fmt.Errorf 위반 사례.
func Validate(amount int64) error {
	if amount <= 0 {
		return fmt.Errorf("invalid amount: %d", amount)
	}
	return nil
}
