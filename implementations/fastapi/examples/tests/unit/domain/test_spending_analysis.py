from src.account.domain.spending_analysis import SpendingAnalysis


def test_create_when_spending_increased_by_more_than_10_percent_then_trend_is_increasing() -> None:
    analysis = SpendingAnalysis.create(
        account_id="account-1",
        analysis_month="2026-07",
        total_amount=15000,
        transaction_count=3,
        previous_total_amount=10000,
    )

    assert analysis.change_from_previous_month == 50
    assert analysis.trend == "INCREASING"
    assert analysis.average_amount == 5000


def test_create_when_spending_decreased_by_more_than_10_percent_then_trend_is_decreasing() -> None:
    analysis = SpendingAnalysis.create(
        account_id="account-1",
        analysis_month="2026-07",
        total_amount=5000,
        transaction_count=1,
        previous_total_amount=10000,
    )

    assert analysis.change_from_previous_month == -50
    assert analysis.trend == "DECREASING"


def test_create_when_the_change_is_within_10_percent_then_trend_is_stable() -> None:
    analysis = SpendingAnalysis.create(
        account_id="account-1",
        analysis_month="2026-07",
        total_amount=10500,
        transaction_count=2,
        previous_total_amount=10000,
    )

    assert analysis.change_from_previous_month == 5
    assert analysis.trend == "STABLE"


def test_create_when_there_was_no_spending_in_either_month_then_0_percent_change_and_stable() -> None:
    analysis = SpendingAnalysis.create(
        account_id="account-1",
        analysis_month="2026-07",
        total_amount=0,
        transaction_count=0,
        previous_total_amount=0,
    )

    assert analysis.change_from_previous_month == 0
    assert analysis.trend == "STABLE"
    assert analysis.average_amount == 0


def test_create_when_there_was_no_spending_last_month_but_spending_this_month_then_100_percent_and_increasing() -> None:
    analysis = SpendingAnalysis.create(
        account_id="account-1",
        analysis_month="2026-07",
        total_amount=3000,
        transaction_count=1,
        previous_total_amount=0,
    )

    assert analysis.change_from_previous_month == 100
    assert analysis.trend == "INCREASING"


def test_create_when_transaction_count_is_0_then_average_amount_is_0_rather_than_dividing_by_zero() -> None:
    analysis = SpendingAnalysis.create(
        account_id="account-1",
        analysis_month="2026-07",
        total_amount=0,
        transaction_count=0,
        previous_total_amount=5000,
    )

    assert analysis.average_amount == 0
