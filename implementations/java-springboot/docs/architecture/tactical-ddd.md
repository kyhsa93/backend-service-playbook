# Tactical Design (Spring Boot / Java)

> For the framework-agnostic principles, see the root [tactical-ddd.md](../../../../docs/architecture/tactical-ddd.md).

## Aggregate Root — `Account`

An Aggregate Root encapsulates business rules and invariants, and state changes happen only through domain methods.

```java
// account/domain/Account.java — actual code (excerpt)
@Entity
@Table(name = "accounts")
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;                       // JPA surrogate key — not the domain identifier

    @Column(nullable = false, unique = true)
    private String accountId;              // domain identifier

    @Embedded
    private Money balance;

    @Enumerated(EnumType.STRING)
    private AccountStatus status;

    @Transient
    private final List<Object> domainEvents = new ArrayList<>();

    protected Account() {}                 // the default constructor JPA requires — protected to block direct external construction

    public static Account create(String ownerId, String email, String currency) {
        Account account = new Account();
        account.accountId = UUID.randomUUID().toString();
        account.ownerId = ownerId;
        account.email = email;
        account.balance = new Money(0, currency);
        account.status = AccountStatus.ACTIVE;
        account.domainEvents.add(new AccountCreatedEvent(/* ... */));
        return account;
    }

    public Transaction deposit(long amount) {
        if (this.status != AccountStatus.ACTIVE) {
            throw new AccountException(AccountException.ErrorCode.DEPOSIT_REQUIRES_ACTIVE_ACCOUNT, "Only an active account can receive a deposit.");
        }
        if (amount <= 0) {
            throw new AccountException(AccountException.ErrorCode.INVALID_AMOUNT, "The amount must be greater than 0.");
        }
        Money money = new Money(amount, this.balance.currency());
        this.balance = this.balance.add(money);
        this.updatedAt = LocalDateTime.now();
        Transaction transaction = Transaction.create(this.accountId, TransactionType.DEPOSIT, money);
        this.pendingTransactions.add(transaction);
        this.domainEvents.add(new MoneyDepositedEvent(/* ... */));
        return transaction;
    }
}
```

**How the core principles are realized:**
- **Created only via a static factory method (`create()`)** — no public constructor is opened; instead it's blocked with `protected Account() {}`. `create()` is the sole path guaranteeing the initial invariants (zero balance, ACTIVE status). See [aggregate-id.md](aggregate-id.md) for details.
- **On an invariant violation, an exception is thrown immediately inside the domain method** — `deposit()` never changes state unless it has already validated status/amount.
- **Other Aggregates are referenced only by ID** — `Account` only holds `ownerId` (a user identifier) as a string, and never references a User object.
- **The transaction boundary = the Aggregate boundary** — a single call to `deposit()` changes both `Account` (balance) and `Transaction` (a child entity, added to the pending list) state together, and a single `save()` commits both together.

`Account` is a pure domain class with no `@Entity` needed — persistence mapping is separated into `infrastructure/persistence/AccountJpaEntity`+`AccountMapper` (see [layer-architecture.md](layer-architecture.md)). The Aggregate design itself (factory method, invariants, event collection) matches the root principle exactly.

---

## Entity — `Transaction`

An Entity is judged for equality by a unique identifier and has a lifecycle. `Transaction` is a child entity of `Account`, created/accessed only through the Aggregate Root.

```java
// account/domain/Transaction.java — actual code
@Entity
@Table(name = "transactions")
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String transactionId;          // domain identifier — the basis for equality

    @Column(nullable = false)
    private String accountId;              // the ID of the owning Aggregate Root (a reference)

    @Embedded
    private Money amount;

    protected Transaction() {}

    static Transaction create(String accountId, TransactionType type, Money amount) {   // package-private
        Transaction transaction = new Transaction();
        transaction.transactionId = UUID.randomUUID().toString();
        transaction.accountId = accountId;
        transaction.type = type;
        transaction.amount = amount;
        transaction.createdAt = LocalDateTime.now();
        return transaction;
    }
}
```

**Why `static Transaction create(...)` is package-private, not `public`**: `Transaction` must only ever be created inside `Account.deposit()`/`Account.withdraw()`, blocking external code (an Application Service, etc.) from creating a `Transaction` directly and bypassing the Aggregate's invariants. Java's package-private access modifier — callable only from within the same package (`account.domain`) — enforces this constraint at compile time.

---

## Value Object — `Money`

A Value Object is an immutable object whose equality is judged by the combination of its attributes. A Java `record` expresses this precisely — field-based `equals()`/`hashCode()` are auto-generated, and since every field is `final`, it cannot be modified after construction.

```java
// account/domain/Money.java — actual code
@Embeddable
public record Money(long amount, String currency) {

    public Money {                          // compact canonical constructor — validation always runs on construction
        if (amount < 0) {
            throw new AccountException(AccountException.ErrorCode.INVALID_MONEY_AMOUNT, "The amount must be 0 or greater.");
        }
    }

    public Money add(Money other) {
        assertSameCurrency(other);
        return new Money(this.amount + other.amount, this.currency);   // returns a new instance — the original stays unchanged
    }

    public boolean isLessThan(Money other) {
        assertSameCurrency(other);
        return this.amount < other.amount;
    }

    private void assertSameCurrency(Money other) {
        if (!this.currency.equals(other.currency)) {
            throw new AccountException(AccountException.ErrorCode.CURRENCY_MISMATCH, "The currencies do not match.");
        }
    }
}
```

- **The compact canonical constructor** (`public Money { ... }`, omitting the parentheses and parameter list) applies validation to every construction path (static factories, deserialization, etc.) — no separate constructor that directly assigns fields can be added.
- **Operations return a new instance** (`add`, `subtract`) — they never modify a field directly, like `this.amount += ...`. A record's fields are `final` from the start, so the compiler enforces this.
- **`@Embeddable`** lets JPA map it inline into `Account`/`Transaction`'s columns — the fact that a Value Object maps directly to a group of DB columns is also part of the domain/ORM coupling described in layer-architecture.md.

**Why a record is used for a Value Object**: a Java record guarantees "immutability + value equality" at the language level, so there's no need to write `equals()`/`hashCode()`/`toString()` by hand. This is the Java idiom corresponding to Kotlin's `data class` or a TypeScript class with `readonly` fields.

---

## Domain Event — e.g. `MoneyDepositedEvent`

Expressed as an immutable record with a past-tense name.

```java
// account/domain/MoneyDepositedEvent.java — the actual code's shape
public record MoneyDepositedEvent(
        String accountId, String email, String transactionId,
        Money amount, Money balanceAfter, LocalDateTime occurredAt) {}
```

`Account` collects events in `@Transient List<Object> domainEvents`, and drains and clears them via `pullDomainEvents()`:

```java
public List<Object> pullDomainEvents() {
    List<Object> events = new ArrayList<>(this.domainEvents);
    this.domainEvents.clear();
    return events;
}
```

Instead of erasing the type via `List<Object>`, as the number of events grows, they could be grouped under a common interface (`sealed interface AccountDomainEvent`, Java 17+) to gain some degree of exhaustiveness for `instanceof` pattern matching — the Java approach corresponding to Kotlin's `sealed interface` + `when` exhaustiveness check. However, Java's `sealed` + `switch` pattern matching (JDK 21) has not been adopted in this repository yet.

The publishing path for a Domain Event (saving to the Outbox table → `OutboxPoller` publishes to SQS → `OutboxConsumer` receives and invokes the matching `OutboxEventHandler` implementation in `application/event/`) is covered in detail in [domain-events.md](domain-events.md).

---

## Aggregate boundary — why `Account` and `Transaction` are bundled together

- **They're created together and share a lifecycle**: `Transaction` is only ever created as a byproduct of a `deposit()`/`withdraw()` call and cannot exist independently.
- **Invariants are always validated together**: the balance (`balance`) and the transaction history (`Transaction`) must always be a matched pair — changing the balance without a transaction record, or vice versa, is an invariant violation.
- **User is referenced via `ownerId`**, not held as a `User` object field — avoiding coupling to another Aggregate (if a user domain existed).

`account/infrastructure/notification/persistence/SentEmail` is not a separate Aggregate from `Account` — since it's a simple history record with no business invariants, it was never modeled as an Aggregate Root in the first place (see [directory-structure.md](directory-structure.md)).

---

## Principle summary

| Principle | Implementation in this repository |
|---|---|
| Business rules live in the Aggregate | `Account.deposit()`/`withdraw()`/`suspend()`, etc. all perform validation + state change + event collection |
| Created only via a static factory | `Account.create()`, with the constructor `protected` |
| Child entities are created package-private | `Transaction.create()` is `static` package-private |
| A Value Object is a `record` | `Money` — always validated via the compact constructor |
| A Domain Event is a past-tense `record` | `MoneyDepositedEvent`, `AccountClosedEvent`, etc. |
| Error messages are typed | The `AccountException.ErrorCode` enum, see [error-handling.md](error-handling.md) |

---

## Harness verification

`harness/src/rules/AggregateNoPublicSetters.java` (rule: `aggregate-no-public-setters`) fails the build if it finds a JavaBean-style `public void setX(...)` method or a Lombok `@Setter` in a `class` declaration file under `domain/` — a regression guard for the principle in the table above that state changes must only happen through a "named domain method" (currently `Account`/`Card`/`Payment`/`Refund` all already follow this pattern, so this automatically catches anyone later reverting an Aggregate back into a class with mutable setters).

`harness/src/rules/NoCrossBcDomainImport.java` (rule: `no-cross-bc-domain-import`) extends the "other Aggregates are referenced only by ID" principle (above) to check across BC boundaries — it fails the build if `<bc>/domain/*.java` imports another BC's `<otherBc>/domain/*`. Unlike `no-cross-aggregate-reference` (see domain-service.md), which only looks at an Aggregate pair within the same BC (Payment/Refund), this rule catches the case where the BCs themselves differ (e.g. `card/domain/` importing `payment/domain/Payment` directly).

---

### Related documents

- [layer-architecture.md](layer-architecture.md) — Domain/JPA layer separation
- [repository-pattern.md](repository-pattern.md) — Repository at the Aggregate level
- [domain-events.md](domain-events.md) — Domain Event publish/receipt, the Outbox
- [aggregate-id.md](aggregate-id.md) — ID generation rules
- [error-handling.md](error-handling.md) — the exception hierarchy, ErrorCode
