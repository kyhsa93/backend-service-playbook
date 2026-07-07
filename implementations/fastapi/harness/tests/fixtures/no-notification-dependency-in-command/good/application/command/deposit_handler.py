from ....outbox.outbox_relay import OutboxRelay


class DepositHandler:
    def __init__(self, outbox_relay: OutboxRelay) -> None:
        self._outbox_relay = outbox_relay
