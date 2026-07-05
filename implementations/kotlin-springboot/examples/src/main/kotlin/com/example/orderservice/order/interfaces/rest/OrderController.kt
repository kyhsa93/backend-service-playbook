package com.example.orderservice.order.interfaces.rest

import com.example.orderservice.order.application.command.CancelOrderCommand
import com.example.orderservice.order.application.command.CancelOrderService
import com.example.orderservice.order.application.command.CreateOrderCommand
import com.example.orderservice.order.application.command.CreateOrderService
import com.example.orderservice.order.application.query.GetOrderResult
import com.example.orderservice.order.application.query.GetOrderService
import com.example.orderservice.order.application.query.GetOrdersResult
import com.example.orderservice.order.application.query.GetOrdersService
import com.example.orderservice.order.domain.OrderException
import com.example.orderservice.order.domain.OrderNotFoundException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/orders")
class OrderController(
    private val createOrderService: CreateOrderService,
    private val cancelOrderService: CancelOrderService,
    private val getOrderService: GetOrderService,
    private val getOrdersService: GetOrdersService,
) {

    @GetMapping
    fun getOrders(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") take: Int,
        @RequestParam(required = false) userId: String?,
        @RequestParam(required = false) status: List<String>?,
    ): GetOrdersResult = getOrdersService.getOrders(page, take, userId, status)

    @GetMapping("/{orderId}")
    fun getOrder(@PathVariable orderId: String): GetOrderResult =
        getOrderService.getOrder(orderId)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun createOrder(@RequestBody request: CreateOrderRequest) {
        val items = request.items.map { CreateOrderCommand.ItemInput(it.itemId, it.name, it.price, it.quantity) }
        createOrderService.create(CreateOrderCommand(request.userId, items))
    }

    @PostMapping("/{orderId}/cancel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun cancelOrder(@PathVariable orderId: String, @RequestBody request: CancelOrderRequest) {
        cancelOrderService.cancel(CancelOrderCommand(orderId, request.reason))
    }

    @ExceptionHandler(OrderNotFoundException::class)
    fun handleNotFound(e: OrderNotFoundException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse(e.message ?: ""))

    @ExceptionHandler(OrderException::class)
    fun handleOrderException(e: OrderException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse(e.message ?: ""))

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArg(e: IllegalArgumentException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse(e.message ?: ""))
}
