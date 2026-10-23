package com.commerce.auth;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class AuthController {
    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("service", "auth-service", "status", "UP");
    }

    @GetMapping("/info")
    public Map<String, String> info() {
        return Map.of("service", "auth-service", "message", "Authentication service demo is running");
    }
}
