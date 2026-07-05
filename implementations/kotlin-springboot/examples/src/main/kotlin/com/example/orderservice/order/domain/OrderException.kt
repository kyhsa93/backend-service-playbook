package com.example.orderservice.order.domain

sealed class OrderException(message: String) : RuntimeException(message)

class OrderNotFoundException(orderId: String) : OrderException("order not found: $orderId")
class OrderAlreadyCancelledException : OrderException("이미 취소된 주문입니다.")
class OrderPaidNotCancellableException : OrderException("결제 완료된 주문은 취소할 수 없습니다.")
