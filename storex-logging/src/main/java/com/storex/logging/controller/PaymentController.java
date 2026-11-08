package com.storex.logging.controller;

import com.storex.payment.PaymentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Controller phục vụ kiểm thử Bài tập 1: Dynamic Log Level
 */
@RestController
@RequestMapping("/api/payment")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping("/process")
    public ResponseEntity<Map<String, Object>> process(@RequestBody Map<String, Object> body) {
        String orderId = (String) body.getOrDefault("orderId", "ORD-DEFAULT");
        double amount = ((Number) body.getOrDefault("amount", 100000.0)).doubleValue();
        String method = (String) body.getOrDefault("paymentMethod", "VNPAY");

        Map<String, Object> response = paymentService.processPayment(orderId, amount, method);
        return ResponseEntity.ok(response);
    }
}
