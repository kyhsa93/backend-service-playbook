package com.example.orderservice.order.application.query;

import com.example.orderservice.order.domain.Order;
import com.example.orderservice.order.domain.OrderFindQuery;
import com.example.orderservice.order.domain.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GetOrdersService {

    private final OrderRepository orderRepository;

    public GetOrdersResult getOrders(int page, int take, String userId, List<String> status) {
        OrderFindQuery query = new OrderFindQuery(page, take, userId, status);
        List<Order> orders = orderRepository.findAll(query);
        long total = orderRepository.countAll(query);

        List<GetOrdersResult.OrderSummary> summaries = orders.stream()
                .map(o -> new GetOrdersResult.OrderSummary(o.getOrderId(), o.getStatus().name(), o.totalAmount()))
                .toList();

        return new GetOrdersResult(summaries, total);
    }
}
