package main

import (
	"regexp"
	"strings"
)

// Names holds several case forms derived from the input domain name
// (PascalCase, e.g. "Coupon", "LoyaltyCategory"). Go's convention
// (directory-structure.md) differs from NestJS's — package names are a single
// lowercase word (no underscores), and file names are snake_case.
type Names struct {
	Domain       string // PascalCase singular, e.g. "LoyaltyCategory"
	DomainLower  string // lowercase concatenated singular (package name / singular REST path noun), e.g. "loyaltycategory"
	DomainCamel  string // camelCase singular (local variable name), e.g. "loyaltyCategory"
	DomainSnake  string // snake_case singular (file name component), e.g. "loyalty_category"
	DomainScream string // SCREAMING_SNAKE_CASE (for error codes), e.g. "LOYALTY_CATEGORY"
	Domains      string // PascalCase plural, e.g. "LoyaltyCategories"
	DomainsLower string // lowercase concatenated plural (plural REST path noun / table name), e.g. "loyaltycategories"
	Recv         string // one-letter receiver for domain methods, e.g. "l"
	ModulePath   string // target app's go.mod module path, e.g. "github.com/example/account-service"
}

var (
	boundaryAcronym = regexp.MustCompile(`([A-Z]+)([A-Z][a-z])`)
	boundaryLower   = regexp.MustCompile(`([a-z0-9])([A-Z])`)
	reSXZChSh       = regexp.MustCompile(`([sxz]|[cs]h)$`)
	reConsonantY    = regexp.MustCompile(`[^aeiouAEIOU]y$`)
)

// toSnakeCase converts a PascalCase/camelCase string to snake_case.
// "LoyaltyCategory" -> "loyalty_category", "Coupon" -> "coupon".
func toSnakeCase(s string) string {
	s = boundaryAcronym.ReplaceAllString(s, "${1}_${2}")
	s = boundaryLower.ReplaceAllString(s, "${1}_${2}")
	return strings.ToLower(s)
}

// naivePluralize is a very simple rule-based pluralizer (+s / +es / y→ies) —
// carried over directly from nestjs create-domain.js's naivePluralize with the
// same rules. Irregular plurals (e.g. Category -> Categories is handled
// correctly by this rule, but exceptional words) need manual touch-up after
// generation.
func naivePluralize(s string) string {
	switch {
	case reSXZChSh.MatchString(s):
		return s + "es"
	case reConsonantY.MatchString(s):
		return s[:len(s)-1] + "ies"
	default:
		return s + "s"
	}
}

func capitalizeFirst(s string) string {
	if s == "" {
		return s
	}
	return strings.ToUpper(s[:1]) + s[1:]
}

func lowerFirst(s string) string {
	if s == "" {
		return s
	}
	return strings.ToLower(s[:1]) + s[1:]
}

// BuildNames computes all the case variants needed for generation from the raw
// domain name (must already be in combined-PascalCase-word form, e.g.
// "LoyaltyCategory") and the target app's go.mod module path.
func BuildNames(raw, modulePath string) Names {
	domain := capitalizeFirst(raw)
	domains := naivePluralize(domain)
	return Names{
		Domain:       domain,
		DomainLower:  strings.ToLower(domain),
		DomainCamel:  lowerFirst(domain),
		DomainSnake:  toSnakeCase(domain),
		DomainScream: strings.ToUpper(toSnakeCase(domain)),
		Domains:      domains,
		DomainsLower: strings.ToLower(domains),
		Recv:         strings.ToLower(domain[:1]),
		ModulePath:   modulePath,
	}
}
