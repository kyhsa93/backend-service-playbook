package com.example.accountservice.account.application.command;

import com.example.accountservice.account.domain.AccountException;
import com.example.accountservice.account.domain.AccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * "종료"(close, 상태 전이)와 구분되는 "삭제"(delete, 데이터 생명주기 관리) 유스케이스.
 * 종료(CLOSED)된 계좌만 soft delete할 수 있다 — 불변식 검증은 Account.delete()가 담당한다(persistence.md 참고).
 */
@Service
@RequiredArgsConstructor
public class DeleteAccountService {

    private final AccountRepository accountRepository;

    public void delete(DeleteAccountCommand command) {
        accountRepository.findByAccountIdAndOwnerId(command.accountId(), command.requesterId())
                .orElseThrow(() -> new AccountException(AccountException.ErrorCode.ACCOUNT_NOT_FOUND, "계좌를 찾을 수 없습니다."));
        accountRepository.delete(command.accountId());
    }
}
