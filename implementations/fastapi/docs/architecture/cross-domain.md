# Cross-Domain Call Pattern

> For the decision principle of **when to go sync vs. async** for BC-to-BC communication, see the root [cross-domain-communication.md](../../../../docs/architecture/cross-domain-communication.md). This document covers how, once **sync calls are chosen**, an Adapter (ACL) is actually implemented in FastAPI/Python, using the two Bounded Contexts that actually exist (`account`, `card`) as an example.

## An actual case in this repository — the Card BC synchronously queries the Account BC

`examples/` has two Bounded Contexts: `account` and `card`. To issue a card (`POST /cards`), it's necessary to confirm **immediately, within the same request**, that the linked account exists and is active, so a synchronous Adapter pattern is used — the async (Outbox/Integration Event) approach is used for the opposite-direction flow, where an account suspension/closure propagates to its cards (see "Domain Event vs Integration Event" in [domain-events.md](domain-events.md)).

## Principles

1. **An Application Handler only calls another domain through an Adapter interface**. It never imports another domain's Repository or Handler directly — the harness's `no-cross-bc-repository-in-application` rule catches, via AST, a direct import from another domain's `domain/repository.py` etc. inside `application/` (an import from the same domain is fine).
2. **The Adapter interface is defined as an ABC in the calling side's `application/adapter/`** — the same placement principle as Repository/Technical Service (see "Technical Service interface" in [layer-architecture.md](layer-architecture.md)).
3. **The Adapter implementation is placed in the calling side's `infrastructure/`**, and it calls the target domain's actual entry point (in this repository, the read interface the target domain exposes, e.g. `AccountQuery`).
4. The Adapter interface defines methods **only in the shape the calling side needs** — it does not expose the target domain's entire API or internal enums.
5. Lookup method naming follows this repository's actual Repository convention as-is (unified under a single `find_accounts` — see [repository-pattern.md](repository-pattern.md)).

## Actual implementation — checking whether an Account is active from the Card BC

```
[Card domain]                                       [Account domain]
  application/
    adapter/
      account_adapter.py       ← AccountAdapter(ABC) + AccountView
    command/
      issue_card_handler.py    (AccountAdapter injected)
  domain/
    repository.py               ← AccountQuery(ABC) — the target Card calls
  infrastructure/
    account_adapter_impl.py    ← AccountAdapterImpl
```

**Step 1 — define the ABC in the calling side's (Card's) `application/adapter/`**

```python
# src/card/application/adapter/account_adapter.py — actual code
@dataclass(frozen=True)
class AccountView:
    account_id: str
    active: bool


class AccountAdapter(ABC):
    @abstractmethod
    async def find_account(self, account_id: str, owner_id: str) -> AccountView | None: ...
```

- `AccountView` is a **Card-only DTO** that doesn't expose Account BC's internal model (`Account` Aggregate, `AccountStatus` enum) as-is — it's translated down to a single `active: bool`, so `AccountAdapter`'s contract is preserved even if Account BC adds more statuses or renames things.
- This ABC follows the same placement principle as `NotificationService` (`application/service/notification_service.py`): the interface lives in Application, the implementation in Infrastructure.

**Step 2 — write the implementation in Infrastructure, calling the read interface Account BC exposes (`AccountQuery`)**

Since this is a monolith with the Account BC in the same process (this repository's current deployment shape), the Adapter implementation is an in-process implementation that directly calls Account BC's CQRS read interface `AccountQuery` (`src/account/domain/repository.py`, a read-only ABC with no write methods). It never references Account's Repository implementation or domain objects directly.

```python
# src/card/infrastructure/account_adapter_impl.py — actual code
class AccountAdapterImpl(AccountAdapter):

    def __init__(self, account_query: AccountQuery) -> None:
        self._account_query = account_query

    async def find_account(self, account_id: str, owner_id: str) -> AccountView | None:
        accounts, _ = await self._account_query.find_accounts(page=0, take=1, account_id=account_id, owner_id=owner_id)
        account = accounts[0] if accounts else None
        # Pass the upstream "account not found" signal (None) straight through as Card domain's None signal (no leaking).
        if account is None:
            return None
        return AccountView(account_id=account.account_id, active=account.status == AccountStatus.ACTIVE)
```

Once the Account BC is split into a separate service, this can be swapped for an `HttpAccountAdapter` (calling an internal HTTP client) that implements the same `AccountAdapter` ABC — **not a single line of the calling side's (Card's) code changes.** This is the point where the Adapter pattern serves as an ACL (Anti-Corruption Layer).

**Step 3 — using the Adapter in the Application Handler**

```python
# src/card/application/command/issue_card_handler.py — actual code
class IssueCardHandler:

    def __init__(self, repo: CardRepository, account_adapter: AccountAdapter) -> None:
        self._repo = repo
        self._account_adapter = account_adapter

    async def execute(self, cmd: IssueCardCommand) -> Card:
        # Look up the linked account via the sync Adapter (ACL) — a sync call because the response (whether issuance is allowed) needs it.
        account = await self._account_adapter.find_account(cmd.account_id, cmd.requester_id)
        if account is None:
            raise LinkedAccountNotFoundError()
        if not account.active:
            raise CardIssueRequiresActiveAccountError()

        card = Card.issue(account_id=cmd.account_id, owner_id=cmd.requester_id, brand=cmd.brand)
        await self._repo.save(card)
        return card
```

**Step 4 — binding via a `Depends` factory**

FastAPI has no module declaration like NestJS's `{ provide: AccountAdapter, useClass: AccountAdapterImpl }`. The binding is replaced by a single `Depends` factory function on the router — see [module-pattern.md](module-pattern.md).

```python
# src/card/interface/rest/card_router.py — actual code
def _account_query(session: AsyncSession = Depends(get_session)) -> AccountQuery:
    return SqlAlchemyAccountRepository(session)


def _account_adapter(account_query: AccountQuery = Depends(_account_query)) -> AccountAdapter:
    return AccountAdapterImpl(account_query)


@router.post("", status_code=201, response_model=IssueCardResponse)
async def issue_card(
    body: IssueCardRequest,
    current_user: CurrentUser = Depends(get_current_user),
    repo: CardRepository = Depends(_repo),
    account_adapter: AccountAdapter = Depends(_account_adapter),
) -> IssueCardResponse:
    card = await IssueCardHandler(repo, account_adapter).execute(
        IssueCardCommand(requester_id=current_user.user_id, account_id=body.account_id, brand=body.brand)
    )
    ...
```

Since `SqlAlchemyAccountRepository` is an implementation that satisfies `AccountRepository(AccountQuery, ABC)`, injecting it narrowed down to the `AccountQuery` type as in `_account_query()` means this lookup path can't accidentally call one of Account's write methods (e.g. `save()`) — see [cqrs-pattern.md](cqrs-pattern.md).

## The opposite direction (Account → Card) — why a sync Adapter isn't used here

The flow where an account suspension/closure affects its cards runs the other way: Account is the **publisher** of the event and Card is the **subscriber**. In this case Account must not need to know Card exists (reversing the dependency direction would create coupling), so instead of a sync Adapter, an Outbox-based Integration Event is used (`account.suspended.v1`/`account.closed.v1`) — the full flow and actual code are laid out in [domain-events.md](domain-events.md#domain-event-vs-integration-event).

## Why it isn't imported directly

- **Dependency inversion**: if `IssueCardHandler` imported a concrete class from `src.account...`, Card domain's Application layer would be coupled to Account domain's internal structure. Depending only on `AccountAdapter` (ABC) limits the coupling to the interface level.
- **Test isolation**: following the Application unit-test pattern in [testing.md](testing.md), replacing `AccountAdapter` with `unittest.mock.AsyncMock` lets `IssueCardHandler` be verified alone, without the Account BC/DB.
- **Supports incremental separation**: when swapping the in-process implementation for an HTTP implementation, only the Adapter implementation (`infrastructure/`) changes — the Application/Domain code doesn't change. The Adapter absorbs the path from monolith to separated services.

---

### Related documents

- [cross-domain-communication.md](../../../../docs/architecture/cross-domain-communication.md) — criteria for choosing sync/async, ACL principles (root)
- [domain-events.md](domain-events.md) — the async Integration Event flow in the opposite direction (Account → Card)
- [layer-architecture.md](layer-architecture.md) — placement principle for Technical Service interfaces
- [repository-pattern.md](repository-pattern.md) — the `find_accounts` unified lookup-method convention
- [cqrs-pattern.md](cqrs-pattern.md) — splitting `AccountQuery`/`CardQuery` read-only interfaces
- [module-pattern.md](module-pattern.md) — `Depends`-based binding
- [testing.md](testing.md) — Application unit testing using an Adapter mock
