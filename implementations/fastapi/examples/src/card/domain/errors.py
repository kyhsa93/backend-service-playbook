from .error_codes import CardErrorCode


class CardError(Exception):
    code: CardErrorCode


class CardNotFoundError(CardError):
    code = CardErrorCode.CARD_NOT_FOUND

    def __init__(self, card_id: str) -> None:
        super().__init__("카드를 찾을 수 없습니다.")
        self.card_id = card_id


class LinkedAccountNotFoundError(CardError):
    code = CardErrorCode.LINKED_ACCOUNT_NOT_FOUND

    def __init__(self) -> None:
        super().__init__("연결할 계좌를 찾을 수 없습니다.")


class CardIssueRequiresActiveAccountError(CardError):
    code = CardErrorCode.CARD_ISSUE_REQUIRES_ACTIVE_ACCOUNT

    def __init__(self) -> None:
        super().__init__("활성 상태의 계좌만 카드를 발급할 수 있습니다.")


class CancelledCardCannotBeSuspendedError(CardError):
    code = CardErrorCode.CANCELLED_CARD_CANNOT_BE_SUSPENDED

    def __init__(self) -> None:
        super().__init__("해지된 카드는 정지할 수 없습니다.")


class CardAlreadySuspendedError(CardError):
    code = CardErrorCode.CARD_ALREADY_SUSPENDED

    def __init__(self) -> None:
        super().__init__("이미 정지된 카드입니다.")


class CardAlreadyCancelledError(CardError):
    code = CardErrorCode.CARD_ALREADY_CANCELLED

    def __init__(self) -> None:
        super().__init__("이미 해지된 카드입니다.")
