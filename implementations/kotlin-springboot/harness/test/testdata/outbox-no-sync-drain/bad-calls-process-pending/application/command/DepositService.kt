class DepositService(private val outboxPoller: OutboxPoller) {
    fun deposit() {
        accountRepository.saveAccount(account)
        outboxPoller.poll()
    }
}
