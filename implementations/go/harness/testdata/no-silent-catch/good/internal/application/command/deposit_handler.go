package command

import "fmt"

func Handle() error {
	err := doSomething()
	if err != nil {
		return fmt.Errorf("deposit: %w", err)
	}
	return nil
}

func doSomething() error { return nil }
