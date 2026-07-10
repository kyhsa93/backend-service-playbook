import pytest

from src.account.domain.errors import CurrencyMismatchError, InvalidMoneyAmountError
from src.account.domain.money import Money


def test_금액이_음수면_생성_시_InvalidMoneyAmountError를_던진다() -> None:
    with pytest.raises(InvalidMoneyAmountError):
        Money(-1, "KRW")


def test_같은_통화끼리_더하면_금액이_더해진_새_Money를_반환한다() -> None:
    result = Money(1000, "KRW").add(Money(500, "KRW"))

    assert result == Money(1500, "KRW")


def test_다른_통화를_더하면_CurrencyMismatchError를_던진다() -> None:
    with pytest.raises(CurrencyMismatchError):
        Money(1000, "KRW").add(Money(500, "USD"))


def test_같은_통화끼리_빼면_금액이_빠진_새_Money를_반환한다() -> None:
    result = Money(1000, "KRW").subtract(Money(300, "KRW"))

    assert result == Money(700, "KRW")


def test_다른_통화를_빼면_CurrencyMismatchError를_던진다() -> None:
    with pytest.raises(CurrencyMismatchError):
        Money(1000, "KRW").subtract(Money(300, "USD"))


def test_is_less_than_금액이_더_작으면_True를_반환한다() -> None:
    assert Money(100, "KRW").is_less_than(Money(200, "KRW")) is True
    assert Money(200, "KRW").is_less_than(Money(200, "KRW")) is False


def test_is_zero_금액이_0이면_True를_반환한다() -> None:
    assert Money(0, "KRW").is_zero() is True
    assert Money(1, "KRW").is_zero() is False


def test_같은_금액과_통화를_가진_Money는_동등하다() -> None:
    assert Money(1000, "KRW") == Money(1000, "KRW")
