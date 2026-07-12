class DepositService(private val outboxRelay: OutboxRelay) {
    fun deposit() {
        accountRepository.saveAccount(account)
        outboxRelay.processPending()
    }
}
