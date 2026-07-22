import pytest

from src.account.domain.errors import CurrencyMismatchError, InvalidMoneyAmountError
from src.account.domain.money import Money


def test_raises_InvalidMoneyAmountError_when_amount_is_negative_on_creation() -> None:
    with pytest.raises(InvalidMoneyAmountError):
        Money(-1, "KRW")


def test_adding_the_same_currency_returns_a_new_Money_with_the_amounts_summed() -> None:
    result = Money(1000, "KRW").add(Money(500, "KRW"))

    assert result == Money(1500, "KRW")


def test_adding_a_different_currency_raises_CurrencyMismatchError() -> None:
    with pytest.raises(CurrencyMismatchError):
        Money(1000, "KRW").add(Money(500, "USD"))


def test_subtracting_the_same_currency_returns_a_new_Money_with_the_amount_subtracted() -> None:
    result = Money(1000, "KRW").subtract(Money(300, "KRW"))

    assert result == Money(700, "KRW")


def test_subtracting_a_different_currency_raises_CurrencyMismatchError() -> None:
    with pytest.raises(CurrencyMismatchError):
        Money(1000, "KRW").subtract(Money(300, "USD"))


def test_is_less_than_returns_True_when_amount_is_smaller() -> None:
    assert Money(100, "KRW").is_less_than(Money(200, "KRW")) is True
    assert Money(200, "KRW").is_less_than(Money(200, "KRW")) is False


def test_is_zero_returns_True_when_amount_is_zero() -> None:
    assert Money(0, "KRW").is_zero() is True
    assert Money(1, "KRW").is_zero() is False


def test_Money_with_the_same_amount_and_currency_are_equal() -> None:
    assert Money(1000, "KRW") == Money(1000, "KRW")
