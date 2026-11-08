package com.storex.logging.appender;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.StackTraceElementProxy;
import ch.qos.logback.core.AppenderBase;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Custom Logback Appender: Tự động gửi cảnh báo real-time lên Discord Webhook
 * khi hệ thống phát sinh log mức ERROR.
 *
 * Tích hợp các giải pháp phòng chống lỗi cấp Enterprise:
 * 1. Cơ chế bất đồng bộ (Non-blocking): Không làm chậm luồng xử lý chính của ứng dụng.
 * 2. Chống đệ quy vô tận (Infinite Recursion Prevention): Sử dụng ThreadLocal cờ kiểm tra.
 * 3. Tự động trích xuất ngữ cảnh MDC (RequestId) từ Bài tập 2.
 * 4. Format Embed message màu đỏ chuẩn Discord Markdown.
 */
public class DiscordAppender extends AppenderBase<ILoggingEvent> {

    private String webhookUrl;
    private String applicationName = "StoreX-Backend";

    // Client HTTP chuẩn Java 11+
    private HttpClient httpClient;
    // ThreadPool riêng biệt chuyên phục vụ gửi Webhook ngầm
    private ExecutorService executorService;

    // CỜ BẢO VỆ CHỐNG ĐỆ QUY VÔ TẬN: Ngăn appender tự gọi lại chính nó khi HTTP Client gặp lỗi
    private final ThreadLocal<Boolean> isSending = ThreadLocal.withInitial(() -> false);

    @Override
    public void start() {
        if (webhookUrl == null || webhookUrl.trim().isEmpty()) {
            addWarn("DiscordAppender: Chưa cấu hình webhookUrl. Appender sẽ không gửi tin nhắn.");
        }
        this.executorService = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "discord-alert-thread");
            t.setDaemon(true); // Daemon thread để không cản trở JVM shutdown
            return t;
        });
        this.httpClient = HttpClient.newBuilder()
                .executor(executorService)
                .connectTimeout(Duration.ofSeconds(3))
                .build();

        super.start();
    }

    @Override
    public void stop() {
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdown();
        }
        super.stop();
    }

    @Override
    protected void append(ILoggingEvent eventObject) {
        // Chỉ xử lý các sự kiện log có mức độ từ ERROR trở lên
        if (eventObject == null || eventObject.getLevel().levelInt < Level.ERROR_INT) {
            return;
        }

        // Nếu webhookUrl chưa được cấu hình hoặc cờ chống đệ quy đang bật -> Thoát ngay
        if (webhookUrl == null || webhookUrl.isBlank() || isSending.get()) {
            return;
        }

        try {
            isSending.set(true);

            // Xây dựng JSON payload chuẩn Discord Webhook (hỗ trợ Embeds màu đỏ rực)
            String payloadJson = buildDiscordPayload(eventObject);

            // Gửi HTTP POST bất đồng bộ tới Discord (Non-blocking hoàn toàn luồng chính)
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(webhookUrl))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofString(payloadJson))
                    .build();

            CompletableFuture<HttpResponse<String>> future = httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString());
            future.whenComplete((response, throwable) -> {
                if (throwable != null) {
                    // Dùng addError của AppenderBase thay vì log.error() để tránh đệ quy
                    addError("Gửi cảnh báo Discord thất bại: " + throwable.getMessage());
                } else if (response.statusCode() >= 400) {
                    addWarn("Discord Webhook trả về mã lỗi: " + response.statusCode() + " - " + response.body());
                }
            });

        } catch (Exception e) {
            addError("Lỗi khởi tạo bản tin Discord Alert: " + e.getMessage());
        } finally {
            isSending.set(false);
        }
    }

    /**
     * Tạo Payload định dạng Discord Webhook với Embed màu đỏ (mã màu decimal: 15158332 / Hex: #E74C3C)
     */
    private String buildDiscordPayload(ILoggingEvent event) {
        String message = escapeJson(event.getFormattedMessage());
        String logger = escapeJson(event.getLoggerName());
        String thread = escapeJson(event.getThreadName());
        String time = Instant.ofEpochMilli(event.getTimeStamp()).toString();

        // Lấy RequestId từ MDC (kế thừa kết quả Bài tập 2)
        Map<String, String> mdc = event.getMDCPropertyMap();
        String requestId = (mdc != null && mdc.containsKey("requestId")) ? mdc.get("requestId") : "N/A";

        // Trích xuất StackTrace nếu có
        StringBuilder stackTraceSnippet = new StringBuilder();
        IThrowableProxy throwableProxy = event.getThrowableProxy();
        if (throwableProxy != null) {
            stackTraceSnippet.append(throwableProxy.getClassName()).append(": ").append(throwableProxy.getMessage()).append("\\n");
            StackTraceElementProxy[] traceElements = throwableProxy.getStackTraceElementProxyArray();
            if (traceElements != null) {
                int maxLines = Math.min(5, traceElements.length);
                for (int i = 0; i < maxLines; i++) {
                    stackTraceSnippet.append("  at ").append(traceElements[i].getSTEAsString()).append("\\n");
                }
            }
        }

        String stackTraceField = "";
        if (stackTraceSnippet.length() > 0) {
            stackTraceField = String.format(",{\"name\":\"Stack Trace\",\"value\":\"```java\\n%s```\",\"inline\":false}",
                    escapeJson(stackTraceSnippet.toString()));
        }

        return String.format(
                "{" +
                "\"content\": \"🚨 **[CẢNH BÁO SỰ CỐ PRODUCTION]** Hệ thống phát hiện Exception mức ERROR!\"," +
                "\"embeds\": [{" +
                "  \"title\": \"🔥 %s - ERROR ALERT\"," +
                "  \"description\": \"%s\"," +
                "  \"color\": 15158332," +
                "  \"timestamp\": \"%s\"," +
                "  \"fields\": [" +
                "    {\"name\": \"Application\", \"value\": \"%s\", \"inline\": true}," +
                "    {\"name\": \"Request ID (MDC)\", \"value\": \"`%s`\", \"inline\": true}," +
                "    {\"name\": \"Thread\", \"value\": \"%s\", \"inline\": true}," +
                "    {\"name\": \"Logger\", \"value\": \"`%s`\", \"inline\": false}" +
                     "%s" +
                "  ]" +
                "}]" +
                "}",
                applicationName, message, time, applicationName, requestId, thread, logger, stackTraceField
        );
    }

    private String escapeJson(String input) {
        if (input == null) return "";
        return input.replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\b", "\\b")
                    .replace("\f", "\\f")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r")
                    .replace("\t", "\\t");
    }

    // Getters and Setters cho cấu hình XML
    public String getWebhookUrl() {
        return webhookUrl;
    }

    public void setWebhookUrl(String webhookUrl) {
        this.webhookUrl = webhookUrl;
    }

    public String getApplicationName() {
        return applicationName;
    }

    public void setApplicationName(String applicationName) {
        this.applicationName = applicationName;
    }
}
