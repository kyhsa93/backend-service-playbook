from .error_codes import CardErrorCode


class CardError(Exception):
    code: CardErrorCode


class CardNotFoundError(CardError):
    code = CardErrorCode.CARD_NOT_FOUND

    def __init__(self, card_id: str) -> None:
        super().__init__("Card not found.")
        self.card_id = card_id


class LinkedAccountNotFoundError(CardError):
    code = CardErrorCode.LINKED_ACCOUNT_NOT_FOUND

    def __init__(self) -> None:
        super().__init__("The account to link could not be found.")


class CardIssueRequiresActiveAccountError(CardError):
    code = CardErrorCode.CARD_ISSUE_REQUIRES_ACTIVE_ACCOUNT

    def __init__(self) -> None:
        super().__init__("Only an active account can be issued a card.")


class CancelledCardCannotBeSuspendedError(CardError):
    code = CardErrorCode.CANCELLED_CARD_CANNOT_BE_SUSPENDED

    def __init__(self) -> None:
        super().__init__("A cancelled card cannot be suspended.")


class CardAlreadySuspendedError(CardError):
    code = CardErrorCode.CARD_ALREADY_SUSPENDED

    def __init__(self) -> None:
        super().__init__("The card is already suspended.")


class CardAlreadyCancelledError(CardError):
    code = CardErrorCode.CARD_ALREADY_CANCELLED

    def __init__(self) -> None:
        super().__init__("The card is already cancelled.")
