from __future__ import annotations

import logging
from datetime import datetime

from ...domain.anomaly_detection_service import AnomalyDetectionService
from ...domain.events import WithdrawalAnomalyDetected
from ...domain.money import Money
from ...domain.transaction_repository import TransactionRepository
from ..service.notification_service import NotificationService

logger = logging.getLogger(__name__)

# How many of the account's own most recent (excluding this one) withdrawals
# AnomalyDetectionService trains its mean/stddev against.
HISTORY_WINDOW = 30


class DetectWithdrawalAnomalyEventHandler:
    """Reacts to MoneyWithdrawn (registered in event_handlers.py alongside
    MoneyWithdrawnEventHandler and CategorizeTransactionEventHandler — build_event_handlers()
    supports multiple subscribers per event type, MoneyWithdrawn's third here) to flag a
    withdrawal that's a statistical outlier against the account's own history. Deliberately
    only ever sends a Notification — it never blocks, reverses, or judges the withdrawal
    itself (the withdrawal already completed before this even runs). This is the design
    constraint that keeps it out of the domain-purity trap the earlier (removed)
    RefundFraudRiskScorer/RefundReasonClassifier fell into (see
    docs/architecture/domain-service.md): a signal that only ever informs a human, never one
    a Domain Service treats as a judgment input.
    """

    def __init__(self, transaction_repo: TransactionRepository, notification_service: NotificationService) -> None:
        self._transaction_repo = transaction_repo
        self._notification_service = notification_service
        # AnomalyDetectionService is a pure Domain Service with no framework dependency —
        # instantiated directly here rather than DI-injected (the same reasoning as
        # TransferEligibilityService in transfer_handler.py).
        self._anomaly_detection_service = AnomalyDetectionService()

    async def handle(self, payload: dict) -> None:
        history = await self._transaction_repo.find_recent_withdrawal_amounts(
            payload["account_id"], payload["transaction_id"], HISTORY_WINDOW
        )
        amount = payload["amount"]["amount"]
        if not self._anomaly_detection_service.is_anomalous(history, amount):
            return

        event = WithdrawalAnomalyDetected(
            account_id=payload["account_id"],
            transaction_id=payload["transaction_id"],
            email=payload["email"],
            amount=Money(**payload["amount"]),
            created_at=datetime.fromisoformat(payload["created_at"]),
        )
        await self._notification_service.notify(event, payload["outbox_event_id"])
        logger.info(
            "Anomalous withdrawal detected, alert sent: account_id=%s transaction_id=%s amount=%s",
            payload["account_id"],
            payload["transaction_id"],
            amount,
        )
