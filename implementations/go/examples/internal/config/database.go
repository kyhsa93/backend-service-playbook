package config

import (
	"fmt"
	"os"
)

type DatabaseConfig struct {
	URL string
}

func LoadDatabaseConfig() (DatabaseConfig, error) {
	url := os.Getenv("DATABASE_URL")
	if url == "" {
		return DatabaseConfig{}, fmt.Errorf("config: DATABASE_URL is required")
	}
	return DatabaseConfig{URL: url}, nil
}
