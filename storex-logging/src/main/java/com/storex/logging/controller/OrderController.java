package com.storex.logging.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * Controller phục vụ kiểm thử Bài tập 2: MDC Request Tracing
 */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private static final Logger log = LoggerFactory.getLogger(OrderController.class);

    @PostMapping("/checkout")
    public ResponseEntity<Map<String, Object>> checkout(@RequestBody Map<String, Object> body) {
        log.info("Bước 1: Tiếp nhận yêu cầu tạo đơn hàng từ Client");
        log.info("Bước 2: Kiểm tra tồn kho cho danh sách sản phẩm: {}", body.get("item"));
        log.info("Bước 3: Tạm khóa số lượng hàng tồn và áp dụng mã giảm giá");
        log.info("Bước 4: Chuyển tiếp yêu cầu thanh toán sang cổng Payment Gateway");

        Map<String, Object> res = new HashMap<>();
        res.put("status", "SUCCESS");
        res.put("message", "Đơn hàng đã được tạo thành công");
        return ResponseEntity.ok(res);
    }
}
