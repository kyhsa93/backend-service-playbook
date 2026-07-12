class CardError(Exception):
    pass


class CardNotFoundError(CardError):
    def __init__(self, card_id: str) -> None:
        super().__init__("카드를 찾을 수 없습니다.")
        self.card_id = card_id


class LinkedAccountNotFoundError(CardError):
    def __init__(self) -> None:
        super().__init__("연결할 계좌를 찾을 수 없습니다.")


class CardIssueRequiresActiveAccountError(CardError):
    def __init__(self) -> None:
        super().__init__("활성 상태의 계좌만 카드를 발급할 수 있습니다.")


class CancelledCardCannotBeSuspendedError(CardError):
    def __init__(self) -> None:
        super().__init__("해지된 카드는 정지할 수 없습니다.")


class CardAlreadySuspendedError(CardError):
    def __init__(self) -> None:
        super().__init__("이미 정지된 카드입니다.")


class CardAlreadyCancelledError(CardError):
    def __init__(self) -> None:
        super().__init__("이미 해지된 카드입니다.")
