from __future__ import annotations

from dataclasses import dataclass

from .errors import CurrencyMismatchError, InvalidMoneyAmountError


@dataclass(frozen=True)
class Money:
    amount: int
    currency: str

    def __post_init__(self) -> None:
        if self.amount < 0:
            raise InvalidMoneyAmountError()

    def add(self, other: Money) -> Money:
        self._assert_same_currency(other)
        return Money(self.amount + other.amount, self.currency)

    def subtract(self, other: Money) -> Money:
        self._assert_same_currency(other)
        return Money(self.amount - other.amount, self.currency)

    def is_less_than(self, other: Money) -> bool:
        self._assert_same_currency(other)
        return self.amount < other.amount

    def is_zero(self) -> bool:
        return self.amount == 0

    def _assert_same_currency(self, other: Money) -> None:
        if self.currency != other.currency:
            raise CurrencyMismatchError()
