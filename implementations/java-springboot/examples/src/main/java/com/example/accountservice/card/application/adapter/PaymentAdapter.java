package com.example.accountservice.card.application.adapter;

import java.time.LocalDateTime;

/**
 * Payment BC를 동기 조회하기 위한 Adapter 인터페이스(ACL). 월간 카드 사용내역 안내(scheduling.md Feature 2)가 카드별 결제 건수·합계를
 * 계산하려면 Payment BC의 결제 내역을 조회해야 하므로 동기 Adapter 패턴을 쓴다 (cross-domain.md 참고,
 * card/application/adapter/AccountAdapter와 동일한 이유).
 *
 * <p>반환 타입은 Card BC가 필요로 하는 최소 집계 형태({@link UsageSummary})로 번역한다 — Payment BC의 Payment 엔티티나
 * 상태(enum)를 노출하지 않는다.
 */
public interface PaymentAdapter {

    UsageSummary summarizeUsage(String cardId, LocalDateTime from, LocalDateTime to);

    /** Card BC가 소유하는 최소 사용내역 통계 뷰. */
    record UsageSummary(long count, long totalAmount) {}
}
