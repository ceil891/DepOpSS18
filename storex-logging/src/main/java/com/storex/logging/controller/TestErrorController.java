package com.storex.logging.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.SQLException;

/**
 * Controller phục vụ kiểm thử:
 * - Bài tập 3: Kích hoạt log ERROR để bắn Webhook lên Discord
 * - Bài tập 4: Sinh log hàng loạt để kiểm tra xoay vòng file (Log Rotation)
 */
@RestController
@RequestMapping("/api/test")
public class TestErrorController {

    private static final Logger log = LoggerFactory.getLogger(TestErrorController.class);

    /**
     * Test Bài tập 3: Ném Exception và ghi log ERROR
     * Custom DiscordAppender sẽ tự động bắt lấy log này và bắn webhook lên Discord
     */
    @GetMapping("/error-db")
    public ResponseEntity<String> triggerDatabaseError() {
        try {
            throw new SQLException("Connection to MySQL Master (10.0.1.25:3306) timeout after 5000ms - Max pool connections reached!");
        } catch (Exception e) {
            log.error("Lỗi nghiêm trọng khi thực thi câu lệnh SQL trừ tiền đơn hàng", e);
        }
        return ResponseEntity.status(500).body("Đã kích hoạt lỗi ERROR thành công! Kiểm tra kênh Discord.");
    }

    /**
     * Test Bài tập 4: Sinh log khối lượng lớn để test cơ chế xoay vòng 10MB
     */
    @GetMapping("/generate-logs")
    public ResponseEntity<String> generateHeavyLogs(@RequestParam(defaultValue = "1000") int count) {
        log.info("Bắt đầu sinh {} dòng log để kiểm tra RollingFileAppender...", count);
        for (int i = 0; i < count; i++) {
            log.info("Dòng log số #{} - Nội dung mô phỏng dữ liệu giao dịch StoreX: Payload=[userId=1001, item=Laptop, price=25000000, timestamp={}]",
                    i, System.currentTimeMillis());
        }
        log.info("Hoàn tất sinh {} dòng log.", count);
        return ResponseEntity.ok("Đã ghi " + count + " dòng log vào file logs/application.log");
    }
}
