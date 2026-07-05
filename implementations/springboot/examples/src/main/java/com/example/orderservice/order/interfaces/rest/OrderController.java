package com.example.orderservice.order.interfaces.rest;

import com.example.orderservice.order.application.command.CancelOrderCommand;
import com.example.orderservice.order.application.command.CancelOrderService;
import com.example.orderservice.order.application.command.CreateOrderCommand;
import com.example.orderservice.order.application.command.CreateOrderService;
import com.example.orderservice.order.application.query.GetOrderResult;
import com.example.orderservice.order.application.query.GetOrderService;
import com.example.orderservice.order.application.query.GetOrdersResult;
import com.example.orderservice.order.application.query.GetOrdersService;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/orders")
@RequiredArgsConstructor
public class OrderController {

    private final CreateOrderService createOrderService;
    private final CancelOrderService cancelOrderService;
    private final GetOrderService getOrderService;
    private final GetOrdersService getOrdersService;

    @GetMapping
    public GetOrdersResult getOrders(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int take,
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) List<String> status
    ) {
        return getOrdersService.getOrders(page, take, userId, status);
    }

    @GetMapping("/{orderId}")
    public GetOrderResult getOrder(@PathVariable String orderId) {
        return getOrderService.getOrder(orderId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public void createOrder(@RequestBody CreateOrderRequest request) {
        List<CreateOrderCommand.ItemInput> items = request.items().stream()
                .map(i -> new CreateOrderCommand.ItemInput(i.itemId(), i.name(), i.price(), i.quantity()))
                .toList();
        createOrderService.create(new CreateOrderCommand(request.userId(), items));
    }

    @PostMapping("/{orderId}/cancel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancelOrder(@PathVariable String orderId, @RequestBody CancelOrderRequest request) {
        cancelOrderService.cancel(new CancelOrderCommand(orderId, request.reason()));
    }

    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(EntityNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse(e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorResponse> handleBadRequest(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArg(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(e.getMessage()));
    }
}
