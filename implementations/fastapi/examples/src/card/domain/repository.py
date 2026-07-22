from abc import ABC, abstractmethod

from .card import Card


class CardQuery(ABC):
    """A read-only interface — for the Query Handler only. Never exposes a write method
    such as `save_card()` (see cqrs-pattern.md). Shares its contract with `CardRepository`
    (the write model) but is a separate type — a Query Handler must always depend only on
    this type (the same as the account domain's AccountQuery).

    The lookup method is unified into a single `find_cards(...)` (the same convention as the
    account domain's find_accounts and the payment domain's find_payments) — what used to be
    split into find_by_id/find_by_account has been unified into a single method + optional
    filter keyword arguments. A single-item lookup is expressed with `take=1`.
    """

    @abstractmethod
    async def find_cards(
        self,
        page: int,
        take: int,
        card_id: str | None = None,
        owner_id: str | None = None,
        account_id: str | None = None,
        status: list[str] | None = None,
    ) -> tuple[list[Card], int]: ...


class CardRepository(CardQuery, ABC):
    @abstractmethod
    async def save_card(self, card: Card) -> None: ...
