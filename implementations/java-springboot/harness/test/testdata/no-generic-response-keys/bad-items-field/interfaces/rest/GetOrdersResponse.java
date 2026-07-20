package com.example.accountservice.order.interfaces.rest;

import java.util.List;

public record GetOrdersResponse(List<String> items, long count) {}
