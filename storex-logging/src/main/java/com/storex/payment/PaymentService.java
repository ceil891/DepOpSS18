package com.storex.payment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * BÀI TẬP 1: Service thuộc package com.storex.payment
 * Dùng để kiểm chứng tính năng Dynamic Log Level qua Spring Boot Actuator.
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    public Map<String, Object> processPayment(String orderId, double amount, String method) {
        // Log INFO luôn xuất hiện theo cấu hình mặc định
        log.info("Bắt đầu xử lý thanh toán cho OrderId: {}, Số tiền: {}, Cổng: {}", orderId, amount, method);

        // Các log DEBUG bên dưới CHỈ xuất hiện khi Actuator đổi log level com.storex.payment sang DEBUG
        log.debug("[DEBUG-PAYMENT] Kiểm tra chữ ký số HashSecret cho OrderId: {}", orderId);
        log.debug("[DEBUG-PAYMENT] Gửi bản tin thanh toán tới API đối tác: {}. Payload: {amount={}, order={}}", method, amount, orderId);
        log.debug("[DEBUG-PAYMENT] Nhận mã phản hồi từ Cổng thanh toán: ResponseCode=00, TransactionId=VNPAY_998877");

        log.info("Thanh toán thành công cho OrderId: {}", orderId);

        Map<String, Object> result = new HashMap<>();
        result.put("orderId", orderId);
        result.put("status", "SUCCESS");
        result.put("transactionId", "TXN-" + System.currentTimeMillis());
        return result;
    }
}
