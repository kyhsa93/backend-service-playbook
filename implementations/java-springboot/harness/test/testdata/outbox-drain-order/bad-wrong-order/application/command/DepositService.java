class DepositService {
    private final OutboxRelay outboxRelay;

    void deposit() {
        outboxRelay.processPending();
        accountRepository.save(account);
    }
}
