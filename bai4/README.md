# Bài tập 4: Phân tích và khắc phục Log Rotation - Chống Tràn Ổ Cứng (Disk Full Prevention)

## 1. Mục tiêu kiến thức
- Hiểu rõ cơ chế xoay vòng và lưu trữ log (**Log Rotation**) trong **Logback**.
- Phân biệt sự khác biệt cốt lõi giữa **`FileAppender`** và **`RollingFileAppender`**.
- Nắm vững cú pháp và các tham số của chính sách kết hợp thời gian và dung lượng: **`SizeAndTimeBasedRollingPolicy`**.
- Hiểu tầm quan trọng của việc nén log định dạng **GZIP (`.gz`)** và thiết lập giới hạn dung lượng tối đa (**`totalSizeCap`**) để bảo vệ hệ thống trước nguy cơ tràn ổ cứng (**Disk 100% Full**).

---

## 2. Tình huống nghiệp vụ & Phân tích nguyên nhân lỗi

### 2.1. Tình huống
Máy chủ VPS của **StoreX** đột ngột ngừng hoạt động, không thể truy cập SSH, trang web báo lỗi `502 Bad Gateway`.
Sau khi kỹ sư vào cứu hộ qua Console của nhà cung cấp Cloud, lệnh `df -h` cho thấy phân vùng ổ cứng chính `/` đã bị đầy **100% (Use% = 100%)**.
Kiểm tra chi tiết thư mục ứng dụng:
```text
$ ls -lh logs/
-rw-r--r-- 1 root root 40G Nov 08 22:00 application.log
```
Chỉ có **duy nhất một file log `application.log`** nhưng dung lượng đã phình to lên tới **40GB** sau 3 tháng chạy liên tục!

Đoạn cấu hình ban đầu trong `logback-spring.xml`:
```xml
<!-- CẤU HÌNH BAN ĐẦU BỊ LỖI -->
<appender name="FILE" class="ch.qos.logback.core.FileAppender">
    <file>logs/application.log</file>
    <!-- LỖI CỐT LÕI: Sử dụng FileAppender cố định, không có bất kỳ cơ chế xoay vòng hay dọn dẹp nào! -->
    <encoder>
        <pattern>%d %p %c{1.} [%t] %m%n</pattern>
    </encoder>
</appender>
```

---

### 2.2. Phân tích nguyên nhân: Tại sao `FileAppender` là "bom nổ chậm" trên Production?

1. **Bản chất của `FileAppender`:**
   - Chỉ mở đúng một file tĩnh `application.log` và liên tục ghi nối tiếp (append) từ ngày này qua tháng khác.
   - **Không có giới hạn kích thước (No Size Limit):** File cứ thế phình to vô hạn.
   - **Không có chính sách thời gian (No Retention Policy):** Log từ 3 tháng trước, thậm chí 1 năm trước vẫn nằm nguyên trong đĩa mà không bao giờ bị xóa.
   - **Không có nén dữ liệu (No Compression):** Log văn bản thuần túy chiếm dụng 100% dung lượng đĩa thực tế.

2. **Hậu quả thảm khốc khi ổ cứng bị đầy 100% (`ENOSPC`):**
   - **Hệ điều hành Linux bị tê liệt:** Không thể tạo file socket trong `/var/run`, không thể tạo file tạm trong `/tmp`.
   - **Cơ sở dữ liệu (MySQL / PostgreSQL / Redis) bị crash:** Do không thể ghi thêm vào Transaction Log (WAL / Redo Log), cơ sở dữ liệu tự động dừng khẩn cấp (Emergency Shutdown) để tránh hỏng dữ liệu.
   - **Không thể điều tra sự cố:** Một file log 40GB không thể mở bằng `vim`, `nano` (bị crash do tràn RAM), lệnh `grep` mất 15-30 phút mới quét xong một chuỗi, khiến thời gian phục hồi hệ thống (MTTR) kéo dài hàng giờ.

---

## 3. Giải pháp kỹ thuật chuẩn hóa

### 3.1. Chuyển đổi sang `RollingFileAppender` với `SizeAndTimeBasedRollingPolicy`

Để giải quyết triệt để vấn đề, Logback cung cấp giải pháp xoay vòng kết hợp cả 2 tiêu chí:
1. **Theo thời gian (Time-based):** Tách file theo từng ngày (`%d{yyyy-MM-dd}`).
2. **Theo dung lượng (Size-based):** Mỗi ngày nếu vượt quá **10MB** thì cắt tiếp thành file phụ thứ `%i` (`.0.log.gz`, `.1.log.gz`).
3. **Tự động nén GZIP (`.gz`):** Giảm đến 90-95% dung lượng lưu trữ trên đĩa.
4. **Tự động xóa log cũ (`maxHistory=30`):** Duy trì tối đa 30 ngày lịch sử.
5. **Chốt chặn tổng dung lượng (`totalSizeCap=3GB`):** Bảo hiểm không cho toàn bộ thư mục log vượt quá 3GB.

### 3.2. Đoạn cấu hình chuẩn hóa hoàn chỉnh

*File: [`SS18/bai4/logback-spring.xml`](file:///e:/BaiTapDeVop/SS18/bai4/logback-spring.xml)*

```xml
<appender name="ROLLING_FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
    <!-- File đang được ghi hiện tại -->
    <file>logs/application.log</file>

    <!-- Chính sách xoay vòng đa chiều: Kích thước + Thời gian -->
    <rollingPolicy class="ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy">
        <!-- Tên file sau khi xoay vòng: application-2026-11-08.0.log.gz -->
        <fileNamePattern>logs/application-%d{yyyy-MM-dd}.%i.log.gz</fileNamePattern>

        <!-- YÊU CẦU 1: Giới hạn kích thước mỗi file không quá 10MB -->
        <maxFileSize>10MB</maxFileSize>

        <!-- YÊU CẦU 2: Lưu trữ tối đa 30 ngày (tự động xóa các ngày cũ hơn) -->
        <maxHistory>30</maxHistory>

        <!-- BẢO HIỂM BẢO VỆ Ổ CỨNG: Tổng dung lượng log không bao giờ vượt quá 3GB -->
        <totalSizeCap>3GB</totalSizeCap>

        <!-- Tự động quét và dọn log quá hạn ngay khi ứng dụng khởi động lại -->
        <cleanHistoryOnStart>true</cleanHistoryOnStart>
    </rollingPolicy>

    <encoder>
        <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] [%X{requestId:-SYSTEM}] %-5level %logger{36} - %msg%n</pattern>
        <charset>UTF-8</charset>
    </encoder>
</appender>
```

---

## 4. Bảng so sánh hiệu quả lưu trữ trước và sau khi tối ưu

| Tiêu chí | Cấu hình cũ (`FileAppender`) | Cấu hình mới (`RollingFileAppender`) | Lợi ích đạt được |
|---|---|---|---|
| **Dung lượng file sau 3 tháng** | **40 GB** (1 file duy nhất) | **Tối đa 3 GB** (được kiểm soát bởi `totalSizeCap`) | **Tiết kiệm > 92.5% ổ đĩa** |
| **Kích thước mỗi file** | 40 GB (không thể mở bằng text editor) | Cố định $\le$ **10 MB** | Dễ dàng tải về máy cá nhân, `grep` chỉ mất 0.1 giây |
| **Nén dữ liệu** | Không nén (Raw text) | Tự động nén **GZIP (`.gz`)** | Text nén đạt tỷ lệ 10:1 (file 10MB nén còn 800KB) |
| **Xóa log cũ** | Không xóa (Phải xóa thủ công bằng lệnh `rm`) | **Tự động xóa sau 30 ngày** (`maxHistory=30`) | Hoàn toàn tự động, loại bỏ lỗi do con người |
| **Nguy cơ sập VPS** | **RẤT CAO (100% xảy ra sau thời gian dài)** | **0% (Được bảo vệ bởi `totalSizeCap`)** | Đảm bảo tính ổn định bền vững cho máy chủ |

---

## 5. Kết quả mong muốn quan sát trong thư mục `logs/`

Sau một thời gian vận hành trong ngày có lưu lượng tải cao, thư mục `logs/` sẽ có cấu trúc ngăn nắp:

```text
logs/
├── application.log                  # 4.2 MB (File hiện tại đang ghi)
├── application-2026-11-08.0.log.gz  # 850 KB (File nén số 0 của ngày 08/11)
├── application-2026-11-08.1.log.gz  # 910 KB (File nén số 1 của ngày 08/11)
├── application-2026-11-08.2.log.gz  # 890 KB (File nén số 2 của ngày 08/11)
├── application-2026-11-07.0.log.gz  # 780 KB (Log của ngày hôm qua)
└── ...                              # Tối đa 30 ngày, các ngày cũ hơn tự biến mất!
```

---

## 6. Kết luận rút ra
- Tuyệt đối **không bao giờ sử dụng `FileAppender`** cho bất kỳ ứng dụng Production nào.
- Luôn luôn sử dụng `RollingFileAppender` kết hợp **`SizeAndTimeBasedRollingPolicy`** có đuôi **`.gz`** và khai báo đầy đủ bộ 3: **`maxFileSize`**, **`maxHistory`**, và **`totalSizeCap`**.
