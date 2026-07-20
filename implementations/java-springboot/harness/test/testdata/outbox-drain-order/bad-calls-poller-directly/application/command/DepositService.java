class DepositService {
    private final OutboxPoller outboxPoller;

    void deposit() {
        accountRepository.save(account);
        outboxPoller.poll();
    }
}
