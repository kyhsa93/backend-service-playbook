import logging

logger = logging.getLogger(__name__)


class Account:
    def deposit(self, amount: int) -> None:
        logger.info("deposited")
