package command

import "os"

func maxDepositAmount() string {
	return os.Getenv("MAX_DEPOSIT_AMOUNT")
}
