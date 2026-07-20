class DepositService {
    private final AccountRepository accountRepository;

    void deposit() {
        accountRepository.save(account);
        // Outbox → 큐 발행/수신은 OutboxPoller/OutboxConsumer가 독립적으로 처리한다.
    }
}
