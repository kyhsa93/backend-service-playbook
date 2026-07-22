// Fixture that mentions Outbox persistence without a transaction annotation
class AccountRepositoryImpl {
    fun save() {
        // writes Outbox row
    }
}
