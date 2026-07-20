package command

import "errors"

// application/는 sentinel을 새로 선언하지 않고 domain의 것을 재사용해야 하는데
// 여기서 직접 errors.New를 호출하는 위반 사례.
func Validate(amount int64) error {
	if amount <= 0 {
		return errors.New("invalid amount")
	}
	return nil
}
