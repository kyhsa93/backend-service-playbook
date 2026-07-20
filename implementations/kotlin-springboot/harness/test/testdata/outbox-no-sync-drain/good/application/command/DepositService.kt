class DepositService(private val accountRepository: AccountRepository) {
    fun deposit() {
        accountRepository.saveAccount(account)
    }
}
