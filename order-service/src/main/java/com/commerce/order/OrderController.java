package com.commerce.order;

import java.math.BigDecimal;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class OrderController {
    private static final List<Order> ORDERS = List.of(
            new Order("order-1000", "customer-42", "sku-100", 1, new BigDecimal("49.00"), "CONFIRMED"),
            new Order("order-1001", "customer-77", "sku-200", 2, new BigDecimal("37.00"), "PROCESSING"));

    @GetMapping("/health")
    public HealthResponse health() {
        return new HealthResponse("order-service", "UP");
    }

    @GetMapping("/orders")
    public List<Order> orders() {
        return ORDERS;
    }

    @GetMapping("/orders/{id}")
    public Order order(@PathVariable String id) {
        return ORDERS.stream()
                .filter(item -> item.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new OrderNotFoundException(id));
    }

    public record Order(String id, String customerId, String sku, int quantity,
                        BigDecimal total, String status) {}
    public record HealthResponse(String service, String status) {}
}
