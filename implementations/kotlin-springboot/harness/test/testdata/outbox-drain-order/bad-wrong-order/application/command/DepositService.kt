class DepositService(private val outboxRelay: OutboxRelay) {
    fun deposit() {
        outboxRelay.processPending()
        accountRepository.save(account)
    }
}
