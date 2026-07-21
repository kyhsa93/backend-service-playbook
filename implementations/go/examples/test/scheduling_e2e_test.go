package test

import (
	"context"
	"net/http"
	"testing"
	"time"

	"github.com/stretchr/testify/require"
)

// getAccountTransactionTypes는 계좌의 모든 거래 타입 목록을 반환한다(조회 실패 시 nil).
func getAccountTransactionTypes(t *testing.T, ownerID, accountID string) []string {
	t.Helper()
	resp := doRequest(t, http.MethodGet, "/accounts/"+accountID+"/transactions?take=50", ownerID, nil)
	if resp.StatusCode != http.StatusOK {
		_ = resp.Body.Close()
		return nil
	}
	body := decodeBody(t, resp)
	raw, ok := body["transactions"].([]any)
	if !ok {
		return nil
	}
	types := make([]string, 0, len(raw))
	for _, item := range raw {
		tx, ok := item.(map[string]any)
		if !ok {
			continue
		}
		if ty, ok := tx["type"].(string); ok {
			types = append(types, ty)
		}
	}
	return types
}

func containsString(items []string, want string) bool {
	for _, s := range items {
		if s == want {
			return true
		}
	}
	return false
}

// TestScheduledInterestPayment은 Scheduler → Task Outbox → Task Queue → Task Consumer →
// Command Service 경로(scheduling.md) 전체를 실제로 구동한다 — 실제 tick을 기다리지
// 않고 testInterestScheduler.EnqueueDailyInterest를 직접 호출해(scheduling.md 예시와
// 동일한 테스트 패턴) 결정론적으로 검증한다.
func TestScheduledInterestPayment(t *testing.T) {
	t.Run("이자가_지급되고_거래내역에_INTEREST가_남는다", func(t *testing.T) {
		owner := "interest-owner-" + time.Now().Format("150405.000000")
		account := createAccountWithEmail(t, owner, owner+"@example.com", "KRW")
		accountID := account["accountId"].(string)

		depositResp := doRequest(t, http.MethodPost, "/accounts/"+accountID+"/deposit", owner, map[string]int{"amount": 1_000_000})
		require.Equal(t, http.StatusCreated, depositResp.StatusCode)

		today := time.Now().UTC()
		require.NoError(t, testInterestScheduler.EnqueueDailyInterest(context.Background(), today))

		// floor(1_000_000 * 0.0001) = 100 → 잔액이 1_000_100이 될 때까지 폴링한다.
		// Scheduler.EnqueueDailyInterest(Task Outbox insert) → taskqueue.Poller(1초 tick,
		// SQS 발행) → taskqueue.Consumer(long polling 수신) → InterestTaskController →
		// ApplyDailyInterestHandler 전체 경로를 거치므로 payment/notification e2e와 동일한
		// 30초 예산을 쓴다.
		waitForAccountBalance(t, owner, accountID, 1_000_100)

		types := getAccountTransactionTypes(t, owner, accountID)
		require.True(t, containsString(types, "INTEREST"), "want an INTEREST transaction, got %v", types)

		// 이자 지급도 계좌 알림 이메일 경로(Domain Event → Outbox → SQS → EventHandler)를
		// 그대로 탄다 — Task Queue(배치 자체)와 Domain Event(그 결과 이메일 알림)가 한
		// 유스케이스 안에서 함께 동작하는 것을 확인한다.
		sentEmail := waitForSentEmail(t, accountID, "InterestPaid")
		require.Equal(t, owner+"@example.com", sentEmail.Recipient)
	})

	t.Run("같은_날짜에_다시_enqueue해도_이자가_중복_지급되지_않는다", func(t *testing.T) {
		owner := "interest-idem-owner-" + time.Now().Format("150405.000000")
		account := createAccountWithEmail(t, owner, owner+"@example.com", "KRW")
		accountID := account["accountId"].(string)

		depositResp := doRequest(t, http.MethodPost, "/accounts/"+accountID+"/deposit", owner, map[string]int{"amount": 1_000_000})
		require.Equal(t, http.StatusCreated, depositResp.StatusCode)

		// 위 서브테스트가 이미 "오늘" 날짜의 dedup_id를 소비했으므로(task_outbox.dedup_id
		// UNIQUE 제약), 여기서도 같은 날짜를 쓰면 이 서브테스트의 계좌는 영영 처리되지
		// 않는다 — 서브테스트 간 시나리오를 분리하기 위해 하루 뒤 날짜를 쓴다(멱등성
		// 검증 자체는 이 날짜 하나를 두 번 enqueue하는 것으로 여전히 유효하다).
		tomorrow := time.Now().UTC().AddDate(0, 0, 1)
		require.NoError(t, testInterestScheduler.EnqueueDailyInterest(context.Background(), tomorrow))
		waitForAccountBalance(t, owner, accountID, 1_000_100)

		// 같은 날짜로 두 번째 enqueue — task_outbox.dedup_id UNIQUE 제약으로 두 번째
		// Task는 애초에 적재되지 않는다(scheduling.md, "Cron 다중 인스턴스 안전성"). 설령
		// 적재됐더라도 Account.ApplyInterest 자체가 날짜 기반으로 멱등하다(Level 1).
		require.NoError(t, testInterestScheduler.EnqueueDailyInterest(context.Background(), tomorrow))

		// "바뀌지 않는다"는 폴링으로 직접 증명할 수 없으므로, 비동기 경로가 안정적으로
		// 왕복할 시간(다른 e2e 테스트가 실측한 예산과 동일한 자릿수)을 준 뒤 최종 값을
		// 확인한다.
		time.Sleep(5 * time.Second)
		require.Equal(t, int64(1_000_100), getAccountBalance(t, owner, accountID))
	})
}

// TestScheduledCardUsageStatement도 동일한 경로를 카드 사용내역 배치로 검증한다.
func TestScheduledCardUsageStatement(t *testing.T) {
	t.Run("월간_사용내역이_발송되고_이메일에_기록된다", func(t *testing.T) {
		owner := "statement-owner-" + time.Now().Format("150405.000000")
		account := createAccountWithEmail(t, owner, owner+"@example.com", "KRW")
		accountID := account["accountId"].(string)

		cardResp := issueCard(t, owner, accountID, "VISA")
		require.Equal(t, http.StatusCreated, cardResp.StatusCode)
		cardID := decodeBody(t, cardResp)["cardId"].(string)

		// 결제가 통과하려면(PaymentAccountAdapter의 잔액 판정) 계좌에 잔액이 있어야 한다.
		depositResp := doRequest(t, http.MethodPost, "/accounts/"+accountID+"/deposit", owner, map[string]int{"amount": 100_000})
		require.Equal(t, http.StatusCreated, depositResp.StatusCode)

		// COMPLETED 결제 2건을 만든다(CreatePaymentHandler는 판정 통과 시 즉시 Complete()
		// 까지 호출하므로 동기적으로 COMPLETED다 — payment.go 참고).
		p1 := createPayment(t, owner, cardID, 10_000)
		require.Equal(t, http.StatusCreated, p1.StatusCode)
		p2 := createPayment(t, owner, cardID, 5_000)
		require.Equal(t, http.StatusCreated, p2.StatusCode)

		// EnqueueMonthlyStatement(now)는 "now가 속한 달의 바로 이전 달"을 대상 period로
		// 삼는다 — 방금 만든 결제들이 이번 달(오늘)에 속하므로, now를 한 달 뒤로 넘겨
		// "이전 달 = 오늘이 속한 달"이 되게 만든다.
		triggerTime := time.Now().UTC().AddDate(0, 1, 0)
		require.NoError(t, testStatementScheduler.EnqueueMonthlyStatement(context.Background(), triggerTime))

		sentEmail := waitForSentEmail(t, accountID, "CardUsageStatement")
		require.Equal(t, owner+"@example.com", sentEmail.Recipient)
		require.Contains(t, sentEmail.Subject, cardID)

		period := time.Now().UTC().Format("2006-01")
		require.Equal(t, period, cardLastStatementSentMonth(t, cardID))
	})

	t.Run("같은_기간에_다시_enqueue해도_명세서가_중복_발송되지_않는다", func(t *testing.T) {
		owner := "statement-idem-owner-" + time.Now().Format("150405.000000")
		account := createAccountWithEmail(t, owner, owner+"@example.com", "KRW")
		accountID := account["accountId"].(string)

		cardResp := issueCard(t, owner, accountID, "VISA")
		require.Equal(t, http.StatusCreated, cardResp.StatusCode)
		cardID := decodeBody(t, cardResp)["cardId"].(string)

		// 위 서브테스트가 이미 "이번 달" period의 dedup_id를 소비했으므로, 여기서는 두 달
		// 뒤를 트리거 삼아 다른 period("다음 달")를 대상으로 한다 — 서브테스트 간
		// 시나리오를 분리하기 위함이며, 멱등성 검증 자체는 이 period 하나를 두 번
		// enqueue하는 것으로 여전히 유효하다.
		triggerTime := time.Now().UTC().AddDate(0, 2, 0)
		require.NoError(t, testStatementScheduler.EnqueueMonthlyStatement(context.Background(), triggerTime))
		waitForSentEmail(t, accountID, "CardUsageStatement")

		// task_outbox.dedup_id UNIQUE 제약으로 두 번째 enqueue는 적재조차 되지 않는다.
		// 설령 적재됐더라도 Card.MarkStatementSent가 같은 period에 대해 멱등하다(Level 1).
		require.NoError(t, testStatementScheduler.EnqueueMonthlyStatement(context.Background(), triggerTime))
		time.Sleep(5 * time.Second)

		require.Equal(t, 1, countSentEmails(t, accountID, "CardUsageStatement"))
		period := time.Now().UTC().AddDate(0, 1, 0).Format("2006-01")
		require.Equal(t, period, cardLastStatementSentMonth(t, cardID))
	})
}

// cardLastStatementSentMonth는 last_statement_sent_month 컬럼을 직접 조회한다 — 이
// 값은 HTTP 응답으로 노출되지 않는 내부 멱등성 마커라 DB로 직접 확인한다.
func cardLastStatementSentMonth(t *testing.T, cardID string) string {
	t.Helper()
	var month string
	require.NoError(t, testDB.QueryRow(
		`SELECT COALESCE(last_statement_sent_month, '') FROM cards WHERE id = $1`, cardID,
	).Scan(&month))
	return month
}

// countSentEmails는 sent_emails에서 (accountID, eventType) 조합의 발송 건수를 센다 —
// 중복 발송되지 않았음을 findSentEmail(단건 존재 여부만 확인)보다 정확히 검증한다.
func countSentEmails(t *testing.T, accountID, eventType string) int {
	t.Helper()
	var count int
	require.NoError(t, testDB.QueryRow(
		`SELECT COUNT(*) FROM sent_emails WHERE account_id = $1 AND event_type = $2`, accountID, eventType,
	).Scan(&count))
	return count
}
