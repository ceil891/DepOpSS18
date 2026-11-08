# Bài tập 3: Custom Appender - Bắn cảnh báo ERROR lên Discord / Slack Webhook

## 1. Mục tiêu kiến thức
- Nắm vững kiến trúc mở rộng của **Logback** thông qua việc kế thừa lớp cơ sở **`AppenderBase<ILoggingEvent>`**.
- Biết cách tích hợp Webhook API của **Discord / Slack** để gửi thông báo tức thời (Real-time Alerting) khi phát sinh sự cố.
- Hiểu và xử lý triệt để bẫy **Đệ quy vô tận (Infinite Recursion / StackOverflowError)** khi xử lý lỗi trong chính Appender.
- Áp dụng kỹ thuật gửi mạng bất đồng bộ (**Asynchronous / Non-blocking**) với **Java 11 `HttpClient`** để không làm gián đoạn luồng nghiệp vụ chính của ứng dụng.

---

## 2. Tình huống nghiệp vụ & Bẫy kỹ thuật

### 2.1. Tình huống
Đội ngũ phát triển và trực vận hành (On-call) của **StoreX** không thể ngồi túc trực 24/24 trên màn hình Kibana để bấm F5 tìm lỗi.
Giám đốc kỹ thuật (CTO) yêu cầu:
> *"Bất kỳ khi nào hệ thống văng ra Exception (ghi nhận log ở cấp độ **ERROR**), ngay lập tức phải có một thông báo đỏ kèm theo chi tiết lỗi và mã truy vết (RequestId) bắn thẳng vào kênh Discord/Slack của đội Dev!"*

Đoạn code khung ban đầu:
```java
// CODE BAN ĐẦU CHƯA HOÀN THIỆN
public class DiscordAppender extends AppenderBase<ILoggingEvent> {
    @Override
    protected void append(ILoggingEvent eventObject) {
        // Code chưa viết phần gửi HTTP Request tới Webhook
        if (eventObject.getLevel().levelInt == Level.ERROR_INT) {
            // TODO: Gửi webhook
        }
    }
}
```

---

### 2.2. Hai bẫy kỹ thuật "chết người" khi viết Custom Appender mạng

#### Bẫy 1: Đệ quy vô tận (Infinite Recursion) $\rightarrow$ Sập toàn bộ JVM
Hãy tưởng tượng kịch bản sau:
1. Ứng dụng phát sinh lỗi: `log.error("Database connection lost")`.
2. `DiscordAppender.append()` được kích hoạt để gửi HTTP POST lên Discord.
3. Không may, đường truyền mạng quốc tế chập chờn hoặc Discord trả về HTTP 500/Timeout.
4. Lập trình viên vô tình viết dòng xử lý lỗi:
   ```java
   catch (Exception e) {
       log.error("Không thể gửi webhook Discord", e); // BẪY CHẾT NGƯỜI!
   }
   ```
5. Lời gọi `log.error()` bên trong khối catch lại kích hoạt chính `DiscordAppender.append()`.
6. Quá trình này lặp lại liên tục hàng triệu lần trong 1 giây $\rightarrow$ **`java.lang.StackOverflowError`**, làm sập toàn bộ ứng dụng Spring Boot!

**Giải pháp chuẩn hóa:**
- Sử dụng biến `ThreadLocal<Boolean> isSending = ThreadLocal.withInitial(() -> false);` để làm cờ bảo vệ (Re-entrance Lock).
- Tuyệt đối không dùng `logger.error()` bên trong Appender. Sử dụng phương thức nội bộ `addError(...)` hoặc `addWarn(...)` do `AppenderBase` cung cấp.

#### Bẫy 2: Nghẽn luồng xử lý chính (Blocking HTTP Request)
- Việc thiết lập kết nối SSL/TLS và gửi HTTP POST qua Internet tới máy chủ Discord (`discord.com`) trung bình mất từ **200ms đến 2.000ms**.
- Nếu gọi HTTP đồng bộ ngay trong hàm `append()`, chính luồng Tomcat của khách hàng mua sắm sẽ bị đứng khựng lại 2 giây chỉ để đợi Discord trả về kết quả!
- **Giải pháp:** Sử dụng `HttpClient.sendAsync(...)` kết hợp với một ThreadPool riêng (`ExecutorService` độc lập với luồng Web).

---

## 3. Giải pháp kỹ thuật chuẩn hóa

### 3.1. Mã nguồn hoàn chỉnh `DiscordAppender.java`

*File: [`SS18/bai3/DiscordAppender.java`](file:///e:/BaiTapDeVop/SS18/bai3/DiscordAppender.java)*

Các điểm kỹ thuật nổi bật:
1. **Lọc Level:** Chỉ xử lý khi `eventObject.getLevel().levelInt >= Level.ERROR_INT`.
2. **Kế thừa MDC:** Tự động lấy `requestId` từ MDC Map (`event.getMDCPropertyMap().get("requestId")`) để đưa vào tin nhắn cảnh báo.
3. **Cắt gọn StackTrace:** Trích xuất tên Exception và tối đa 5 dòng đầu tiên của StackTrace để đưa vào khối code block Markdown của Discord (````java ... ````), tránh vượt giới hạn ký tự (2000 chars) của Discord API.
4. **Discord Embed Payload:** Thiết lập màu đỏ đặc trưng `#E74C3C` (decimal `15158332`).

### 3.2. Cấu hình trong `logback-spring.xml`

```xml
<!-- 1. Custom Discord Webhook Appender -->
<appender name="DISCORD" class="com.storex.logging.appender.DiscordAppender">
    <!-- Đọc Webhook URL từ biến môi trường máy chủ -->
    <webhookUrl>${DISCORD_WEBHOOK_URL:-https://discord.com/api/webhooks/dummy/url}</webhookUrl>
    <applicationName>StoreX-ECommerce</applicationName>
</appender>

<!-- 2. Bọc thêm AsyncAppender để chống nghẽn hàng đợi -->
<appender name="ASYNC_DISCORD" class="ch.qos.logback.classic.AsyncAppender">
    <appender-ref ref="DISCORD" />
    <queueSize>512</queueSize>
    <neverBlock>true</neverBlock>
</appender>

<root level="INFO">
    <appender-ref ref="CONSOLE" />
    <appender-ref ref="ASYNC_DISCORD" />
</root>
```

---

## 4. Hướng dẫn kiểm thử thực tế với Discord

### Bước 1: Tạo Webhook trên Server Discord của bạn
1. Mở Discord $\rightarrow$ Chọn Server của bạn $\rightarrow$ Chọn Kênh text (ví dụ: `#dev-alerts`).
2. Nhấp vào icon **Bánh răng (Edit Channel)** $\rightarrow$ Chọn tab **Integrations** $\rightarrow$ **Webhooks** $\rightarrow$ **New Webhook**.
3. Đặt tên (ví dụ: `StoreX Error Bot`) và bấm **Copy Webhook URL**.
   URL có dạng:
   ```text
   https://discord.com/api/webhooks/1234567890/abcdefg...
   ```

---

### Bước 2: Thiết lập biến môi trường và chạy ứng dụng
Thiết lập URL vừa copy vào biến môi trường:
```bash
# PowerShell
$env:DISCORD_WEBHOOK_URL="https://discord.com/api/webhooks/1234567890/abcdefg..."

# Linux / Bash
export DISCORD_WEBHOOK_URL="https://discord.com/api/webhooks/1234567890/abcdefg..."
```

---

### Bước 3: Kích hoạt một lỗi ERROR thử nghiệm
Gửi request tới API thử nghiệm gây lỗi:
```bash
curl -X GET http://localhost:8080/api/test/error-db
```

Đoạn code trong Controller:
```java
@GetMapping("/api/test/error-db")
public ResponseEntity<String> triggerDbError() {
    try {
        throw new SQLException("Connection to Payment-MySQL timeout after 5000ms");
    } catch (Exception e) {
        log.error("Lỗi DB khi thực hiện trừ tiền đơn hàng", e);
    }
    return ResponseEntity.status(500).body("Error triggered");
}
```

---

### Bước 4: Kết quả hiển thị trên Discord
Ngay lập tức, trong kênh chat Discord `#dev-alerts`, thông báo màu đỏ rực xuất hiện với nội dung chi tiết:

```text
🚨 [CẢNH BÁO SỰ CỐ PRODUCTION] Hệ thống phát hiện Exception mức ERROR!

┌───────────────────────────────────────────────────────────────┐
│ 🔥 StoreX-ECommerce - ERROR ALERT                            │
│ Lỗi DB khi thực hiện trừ tiền đơn hàng                        │
│                                                               │
│ Application: StoreX-ECommerce                                 │
│ Request ID (MDC): 4a5b6c7d-8e9f-0123-4567-89abcdef0123       │
│ Thread: http-nio-8080-exec-3                                  │
│ Logger: com.storex.logging.controller.TestErrorController    │
│                                                               │
│ Stack Trace:                                                  │
│ java.sql.SQLException: Connection to Payment-MySQL timeout... │
│   at com.storex.TestController.triggerDbError(TestController:25)
│   at java.base/jdk.internal.reflect.NativeMethodAccessorImpl...│
└───────────────────────────────────────────────────────────────┘
```

Dev chỉ cần bấm copy mã `Request ID (MDC)` và dán vào Kibana là ra ngay trọn vẹn lịch sử request!

---

## 5. Kết luận rút ra
- Custom Appender là giải pháp cực kỳ mạnh mẽ để tự động hóa quy trình phản ứng sự cố (**Incident Response**).
- Kỹ thuật phi đồng bộ (`sendAsync`) kết hợp với cờ chống đệ quy (`isSending`) là bắt buộc để đảm bảo an toàn cho ứng dụng chính.
