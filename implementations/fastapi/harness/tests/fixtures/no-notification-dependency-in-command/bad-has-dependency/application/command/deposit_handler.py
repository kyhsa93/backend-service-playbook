from ...application.service.notification_service import NotificationService


class DepositHandler:
    def __init__(self, notification_service: NotificationService) -> None:
        self._notification_service = notification_service
