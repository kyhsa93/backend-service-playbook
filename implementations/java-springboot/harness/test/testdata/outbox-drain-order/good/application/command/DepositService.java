class DepositService {
    private final AccountRepository accountRepository;

    void deposit() {
        accountRepository.save(account);
        // Outbox → queue publish/consume is handled independently by OutboxPoller/OutboxConsumer.
    }
}
