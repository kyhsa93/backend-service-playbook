from ....outbox.outbox_writer import OutboxWriter


class DepositHandler:
    def __init__(self, outbox_writer: OutboxWriter) -> None:
        self._outbox_writer = outbox_writer
