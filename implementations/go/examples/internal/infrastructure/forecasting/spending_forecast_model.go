// Package forecasting holds command.SpendingForecastModel's real
// implementation — a Technical Service (see root
// docs/architecture/domain-service.md), placed here the same way
// internal/infrastructure/llm holds the NL transaction-history RAG
// Technical Services' implementations, one infrastructure package per
// technical concern.
package forecasting

import (
	"math"

	"github.com/example/account-service/internal/application/command"
	"github.com/example/account-service/internal/domain/account"
)

const (
	highConfidenceRSquared   = 0.7
	mediumConfidenceRSquared = 0.3
)

// SpendingForecastModelImpl is the real implementation of
// command.SpendingForecastModel — ordinary least squares over
// (monthIndex, totalAmount). A genuine trained model (its two parameters,
// slope and intercept, are fit fresh from each account's own history every
// time the scheduled job runs) rather than a hardcoded rule, while staying
// dependency-free and explainable. x is just the position of the month
// within the trailing history (0, 1, 2, ...), not a calendar value, so a
// gap month (an account with no analysis for some month) doesn't skew the
// fit — callers pass in only the months that actually exist.
type SpendingForecastModelImpl struct{}

var _ command.SpendingForecastModel = (*SpendingForecastModelImpl)(nil)

func NewSpendingForecastModelImpl() *SpendingForecastModelImpl {
	return &SpendingForecastModelImpl{}
}

func (m *SpendingForecastModelImpl) Predict(history []command.SpendingHistoryPoint) command.SpendingForecastPrediction {
	n := len(history)
	xs := make([]float64, n)
	ys := make([]float64, n)
	var xSum, ySum float64
	for i, point := range history {
		xs[i] = float64(i)
		ys[i] = float64(point.TotalAmount)
		xSum += xs[i]
		ySum += ys[i]
	}
	xMean := xSum / float64(n)
	yMean := ySum / float64(n)

	var numerator, denominator float64
	for i := 0; i < n; i++ {
		numerator += (xs[i] - xMean) * (ys[i] - yMean)
		denominator += (xs[i] - xMean) * (xs[i] - xMean)
	}
	// denominator is 0 only when n == 1, which minHistoryMonthsForForecast
	// (>= 3, see forecast_spending_handler.go) already rules out for every
	// real caller — guarded here anyway so this stays correct in isolation.
	var slope float64
	if denominator != 0 {
		slope = numerator / denominator
	}
	intercept := yMean - slope*xMean

	nextMonthIndex := float64(n)
	predictedAmount := int64(math.Round(intercept + slope*nextMonthIndex))
	if predictedAmount < 0 {
		predictedAmount = 0
	}

	var ssTotal, ssResidual float64
	for i := 0; i < n; i++ {
		ssTotal += (ys[i] - yMean) * (ys[i] - yMean)
		residual := ys[i] - (intercept + slope*xs[i])
		ssResidual += residual * residual
	}
	// A perfectly flat history (ssTotal == 0) is a perfect fit by
	// definition, not an undefined one — 0/0 would otherwise produce NaN.
	var rSquared float64 = 1
	if ssTotal != 0 {
		rSquared = 1 - ssResidual/ssTotal
	}

	confidence := account.ForecastConfidenceLow
	switch {
	case rSquared >= highConfidenceRSquared:
		confidence = account.ForecastConfidenceHigh
	case rSquared >= mediumConfidenceRSquared:
		confidence = account.ForecastConfidenceMedium
	}

	return command.SpendingForecastPrediction{PredictedAmount: predictedAmount, Confidence: confidence}
}
