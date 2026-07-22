from unittest.mock import AsyncMock

import pytest

from src.account.application.command.transfer_handler import TransferCommand, TransferHandler
from src.account.domain.account import Account
from src.account.domain.errors import AccountNotFoundError, InsufficientBalanceError


@pytest.fixture
def repo() -> AsyncMock:
    return AsyncMock()


@pytest.mark.asyncio
async def test_execute_saves_both_accounts_when_approved(repo) -> None:
    source = Account.create(owner_id="owner-1", currency="KRW", email="owner1@example.com")
    source.deposit(10000)
    source.pull_events()
    target = Account.create(owner_id="owner-2", currency="KRW", email="owner2@example.com")
    target.pull_events()
    repo.find_accounts.side_effect = [([source], 1), ([target], 1)]
    handler = TransferHandler(repo)

    result = await handler.execute(
        TransferCommand(
            source_account_id=source.account_id,
            target_account_id=target.account_id,
            requester_id="owner-1",
            amount=4000,
        )
    )

    assert result.source_transaction.type == "WITHDRAWAL"
    assert result.target_transaction.type == "DEPOSIT"
    assert result.source_transaction.reference_id == result.transfer_id
    assert result.target_transaction.reference_id == result.transfer_id
    assert source.balance.amount == 6000
    assert target.balance.amount == 4000
    assert repo.save_account.await_count == 2
    repo.save_account.assert_any_await(source)
    repo.save_account.assert_any_await(target)


@pytest.mark.asyncio
async def test_execute_raises_an_error_and_does_not_save_when_withdrawal_account_cannot_be_found(repo) -> None:
    repo.find_accounts.return_value = ([], 0)
    handler = TransferHandler(repo)

    with pytest.raises(AccountNotFoundError):
        await handler.execute(
            TransferCommand(
                source_account_id="missing", target_account_id="account-2", requester_id="owner-1", amount=1000
            )
        )

    repo.save_account.assert_not_called()


@pytest.mark.asyncio
async def test_execute_raises_an_error_and_does_not_save_when_balance_is_insufficient(repo) -> None:
    source = Account.create(owner_id="owner-1", currency="KRW", email="owner1@example.com")
    source.deposit(1000)
    source.pull_events()
    target = Account.create(owner_id="owner-2", currency="KRW", email="owner2@example.com")
    target.pull_events()
    repo.find_accounts.side_effect = [([source], 1), ([target], 1)]
    handler = TransferHandler(repo)

    with pytest.raises(InsufficientBalanceError):
        await handler.execute(
            TransferCommand(
                source_account_id=source.account_id,
                target_account_id=target.account_id,
                requester_id="owner-1",
                amount=5000,
            )
        )

    repo.save_account.assert_not_called()
