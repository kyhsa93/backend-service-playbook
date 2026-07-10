class DepositService {
    private final OutboxRelay outboxRelay;

    void deposit() {
        accountRepository.save(account);
    }
}
