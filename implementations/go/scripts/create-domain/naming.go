package main

import (
	"regexp"
	"strings"
)

// Names는 입력받은 도메인 이름(PascalCase, 예: "Coupon", "LoyaltyCategory")에서
// 파생한 여러 케이스 형태를 담는다. Go 컨벤션(directory-structure.md)은 NestJS와
// 다르다 — 패키지명은 소문자 단일 단어(언더스코어 없음), 파일명은 snake_case다.
type Names struct {
	Domain       string // PascalCase 단수, 예: "LoyaltyCategory"
	DomainLower  string // 소문자 이어붙임 단수(패키지명/REST 경로 단수 명사), 예: "loyaltycategory"
	DomainCamel  string // camelCase 단수(로컬 변수명), 예: "loyaltyCategory"
	DomainSnake  string // snake_case 단수(파일명 컴포넌트), 예: "loyalty_category"
	DomainScream string // SCREAMING_SNAKE_CASE(에러 코드용), 예: "LOYALTY_CATEGORY"
	Domains      string // PascalCase 복수, 예: "LoyaltyCategories"
	DomainsLower string // 소문자 이어붙임 복수(REST 경로 복수 명사 / 테이블명), 예: "loyaltycategories"
	Recv         string // 도메인 메서드 리시버 한 글자, 예: "l"
	ModulePath   string // 대상 앱의 go.mod module 경로, 예: "github.com/example/account-service"
}

var (
	boundaryAcronym = regexp.MustCompile(`([A-Z]+)([A-Z][a-z])`)
	boundaryLower   = regexp.MustCompile(`([a-z0-9])([A-Z])`)
	reSXZChSh       = regexp.MustCompile(`([sxz]|[cs]h)$`)
	reConsonantY    = regexp.MustCompile(`[^aeiouAEIOU]y$`)
)

// toSnakeCase는 PascalCase/camelCase 문자열을 snake_case로 바꾼다.
// "LoyaltyCategory" -> "loyalty_category", "Coupon" -> "coupon".
func toSnakeCase(s string) string {
	s = boundaryAcronym.ReplaceAllString(s, "${1}_${2}")
	s = boundaryLower.ReplaceAllString(s, "${1}_${2}")
	return strings.ToLower(s)
}

// naivePluralize는 아주 단순한 규칙 기반 복수형이다(+s / +es / y→ies) — nestjs
// create-domain.js의 naivePluralize와 동일한 규칙을 그대로 옮긴 것이다. 불규칙
// 복수형(예: Category -> Categories는 이 규칙으로 맞게 처리되지만, 예외적인
// 단어는 생성 후 수동으로 다듬어야 한다).
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

// BuildNames는 원본 도메인 이름(이미 PascalCase 단어 결합 형태여야 한다 — 예:
// "LoyaltyCategory")과 대상 앱의 go.mod module 경로로부터 생성에 필요한 모든
// 케이스 변형을 계산한다.
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
