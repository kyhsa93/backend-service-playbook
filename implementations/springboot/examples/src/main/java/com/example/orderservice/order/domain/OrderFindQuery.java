package com.example.orderservice.order.domain;

import java.util.List;

public record OrderFindQuery(int page, int take, String userId, List<String> status) {}
