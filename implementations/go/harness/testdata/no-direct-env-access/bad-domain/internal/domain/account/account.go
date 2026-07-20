package account

import "os"

func DefaultCurrency() string {
	return os.Getenv("DEFAULT_CURRENCY")
}
