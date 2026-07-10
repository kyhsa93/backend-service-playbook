class DepositService(private val outboxRelay: OutboxRelay) {
    fun deposit() {
        accountRepository.save(account)
        outboxRelay.processPending()
    }
}
