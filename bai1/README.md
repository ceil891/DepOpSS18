# Bài tập 1: Thay đổi Log Level không cần Restart - Dynamic Log Level với Spring Boot Actuator

## 1. Mục tiêu kiến thức
- Hiểu rõ cơ chế quản lý Log Level tại thời điểm thực thi (Runtime Logging Management) thông qua **Spring Boot Actuator**.
- Nắm vững cách cấu hình phơi bày (Expose) các Management Endpoints trong `application.yml`.
- Thành thạo việc sử dụng HTTP REST API (Postman / cURL) để đọc và điều chỉnh cấp độ log của từng Package/Class cụ thể mà **không cần restart ứng dụng**, đảm bảo tiêu chuẩn **Zero Downtime**.
- Nắm vững các lưu ý bảo mật (Security Considerations) khi kích hoạt các Endpoint quản trị trên môi trường Production.

---

## 2. Tình huống nghiệp vụ & Bẫy kỹ thuật

### 2.1. Tình huống
Hệ thống thanh toán **StoreX** đang vận hành trực tiếp trên Production thì xuất hiện lỗi thanh toán ngắt quãng từ cổng VNPAY/MoMo. Để điều tra nguyên nhân sâu xa, kỹ sư cần bật mức log **`DEBUG`** cho package `com.storex.payment`.
Tuy nhiên, hệ thống đang phục vụ hàng nghìn giao dịch mỗi phút:
> **Tuyệt đối không được phép Restart ứng dụng** (Restart sẽ làm đứt kết nối của khách hàng đang giao dịch, hủy giỏ hàng, và gây downtime dịch vụ).

Đoạn cấu hình ban đầu trong `application.yml`:
```yaml
# CẤU HÌNH BAN ĐẦU BỊ LỖI
management:
  endpoints:
    web:
      exposure:
        # LỖI: Chỉ expose "health" và "info", endpoint "loggers" bị ẩn hoàn toàn
        include: health,info
```

### 2.2. Bẫy kỹ thuật: Tại sao không thể gọi API thay đổi log?
- Theo mặc định trong Spring Boot, vì lý do an toàn thông tin, chỉ có hai endpoint `health` và `info` được mở ra qua giao thức HTTP Web.
- Endpoint **`loggers`** thuộc nhóm nhạy cảm (có khả năng can thiệp trực tiếp vào hành vi của hệ thống). Khi chưa được khai báo trong `management.endpoints.web.exposure.include`, mọi request gửi tới `/actuator/loggers` đều trả về lỗi **HTTP 404 Not Found**.

---

## 3. Giải pháp kỹ thuật chuẩn hóa

### 3.1. Cấu hình `application.yml` đã sửa
Bổ sung `loggers` vào danh sách `include` của Actuator:

```yaml
server:
  port: 8080

spring:
  application:
    name: storex-payment-service

logging:
  level:
    root: INFO
    # Mức log mặc định trên Production là INFO (không in log DEBUG thừa để tiết kiệm đĩa/CPU)
    com.storex.payment: INFO

management:
  endpoints:
    web:
      exposure:
        # ĐÃ SỬA: Mở thêm endpoint loggers cho môi trường Web
        include: health,info,loggers
  endpoint:
    loggers:
      enabled: true
```

### 3.2. Cơ chế hoạt động bên dưới của Spring Boot Actuator
Khi gọi API `/actuator/loggers/{name}`:
1. Spring Boot Actuator ủy quyền cho component `LoggersEndpoint`.
2. `LoggersEndpoint` giao tiếp trực tiếp với `LoggingSystem` (đại diện cho Logback `LoggerContext`).
3. `LoggerContext` tìm kiếm logger tương ứng với package `com.storex.payment` trong bộ nhớ RAM và cập nhật thuộc tính `level = Level.DEBUG`.
4. Toàn bộ tiến trình thay đổi diễn ra trong vòng **vài mili-giây**, hoàn toàn trong bộ nhớ (In-Memory), không cần nạp lại file cấu hình và không làm gián đoạn bất kỳ kết nối mạng nào!

---

## 4. Hướng dẫn kiểm thử chi tiết qua Postman & cURL

### Bước 1: Kiểm tra trạng thái Log Level ban đầu
Gửi request HTTP GET để xem trạng thái hiện tại:

```bash
curl -X GET http://localhost:8080/actuator/loggers/com.storex.payment
```

**Response trả về (JSON):**
```json
{
  "configuredLevel": "INFO",
  "effectiveLevel": "INFO"
}
```

---

### Bước 2: Bật mức log DEBUG bằng HTTP POST
Sử dụng cURL hoặc Postman gửi request đổi log level sang `DEBUG`:

```bash
curl -X POST http://localhost:8080/actuator/loggers/com.storex.payment \
     -H "Content-Type: application/json" \
     -d '{"configuredLevel": "DEBUG"}'
```

**Mô tả trên Postman:**
- **Method:** `POST`
- **URL:** `http://localhost:8080/actuator/loggers/com.storex.payment`
- **Headers:** `Content-Type: application/json`
- **Body (raw JSON):**
  ```json
  {
    "configuredLevel": "DEBUG"
  }
  ```
- **Mã phản hồi mong muốn:** `204 No Content` (thành công).

---

### Bước 3: Xác thực lại trạng thái sau khi đổi
Gửi lại request GET:
```bash
curl -X GET http://localhost:8080/actuator/loggers/com.storex.payment
```

**Response trả về:**
```json
{
  "configuredLevel": "DEBUG",
  "effectiveLevel": "DEBUG"
}
```

---

### Bước 4: Kiểm tra log thực tế trên Console
Khi có request gửi tới endpoint nghiệp vụ `/api/payment/process`:
```bash
curl -X POST http://localhost:8080/api/payment/process \
     -H "Content-Type: application/json" \
     -d '{"orderId":"ORD-1234","amount":500000}'
```

**Kết quả quan sát trên Console:**
```text
2026-11-08 22:50:00.123 [http-nio-8080-exec-1] INFO  com.storex.payment.PaymentService - Processing payment for order ORD-1234
2026-11-08 22:50:00.125 [http-nio-8080-exec-1] DEBUG com.storex.payment.PaymentService - [DEBUG-PAYMENT] Gateway payload: {"merchantId":"VNPAY_STOREX","hashSecret":"***"}
2026-11-08 22:50:00.128 [http-nio-8080-exec-1] DEBUG com.storex.payment.PaymentService - [DEBUG-PAYMENT] Raw response from gateway: statusCode=00, transNo=998877
```
> **Đạt kết quả:** Các dòng log `DEBUG` đã lập tức xuất hiện trên màn hình console mà **không cần restart ứng dụng**!

---

### Bước 5: Khôi phục lại mức INFO sau khi gỡ lỗi xong
Sau khi tìm ra nguyên nhân lỗi, cần hạ log level về lại `INFO` để tránh ghi log quá nhiều làm chậm hệ thống và đầy ổ cứng:

```bash
curl -X POST http://localhost:8080/actuator/loggers/com.storex.payment \
     -H "Content-Type: application/json" \
     -d '{"configuredLevel": "INFO"}'
```
*(Hoặc gửi `{"configuredLevel": null}` để logger tự động kế thừa level từ root logger).*

---

## 5. Khuyến nghị Bảo mật trên Production
1. **Bảo vệ Actuator Endpoint:** Không bao giờ mở public các cổng actuator ra ngoài Internet. Phải bọc qua Spring Security hoặc tách biệt port quản trị riêng:
   ```yaml
   management:
     server:
       port: 8081 # Chỉ cho phép truy cập từ mạng nội bộ (Internal VPN / Management Subnet)
   ```
2. **Xác thực và Phân quyền:** Yêu cầu quyền `ROLE_ACTUATOR` hoặc `ROLE_ADMIN` khi gọi API thay đổi log level.
