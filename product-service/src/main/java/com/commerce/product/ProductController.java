package com.commerce.product;

import java.math.BigDecimal;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class ProductController {
    private static final List<Product> PRODUCTS = List.of(
            new Product("sku-100", "Everyday Backpack", new BigDecimal("49.00")),
            new Product("sku-200", "Insulated Bottle", new BigDecimal("18.50")));

    @GetMapping("/health")
    public HealthResponse health() {
        return new HealthResponse("product-service", "UP");
    }

    @GetMapping("/products")
    public List<Product> products() {
        return PRODUCTS;
    }

    @GetMapping("/products/{sku}")
    public Product product(@PathVariable String sku) {
        return PRODUCTS.stream()
                .filter(item -> item.sku().equals(sku))
                .findFirst()
                .orElseThrow(() -> new ProductNotFoundException(sku));
    }

    public record Product(String sku, String name, BigDecimal price) {}
    public record HealthResponse(String service, String status) {}
}
