package com.example.accountservice.card.domain;

import java.util.List;

/**
 * {@code findCards} 조회 조건 — 단건/필터/페이지네이션 조회를 모두 이 하나의 record로 표현한다(account/domain/AccountFindQuery,
 * payment/domain/PaymentFindQuery와 동일한 패턴). 단건 조회(카드 상세, Payment BC의 ACL 조회)는 {@code cardId}/{@code
 * ownerId}를 채우고 {@code take}를 1로 설정해 호출한다. Account BC의 Integration Event 반응(정지/해지)은 {@code
 * accountId} +{@code statuses}만 채워 대상 카드 목록을 조회한다.
 */
public record CardFindQuery(
        int page,
        int take,
        String cardId,
        String ownerId,
        String accountId,
        List<CardStatus> statuses) {}
