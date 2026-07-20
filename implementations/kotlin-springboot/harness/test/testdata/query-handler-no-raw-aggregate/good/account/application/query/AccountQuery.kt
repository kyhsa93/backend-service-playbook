package com.example.accountservice.account.application.query

import com.example.accountservice.account.domain.Account

// @Service가 없는 읽기 전용 포트 인터페이스 — Query Service 내부에서만 쓰이므로 raw Account를
// 반환해도 대상이 아니다(cqrs-pattern.md).
interface AccountQuery {
    fun findAccounts(): List<Account>
}
