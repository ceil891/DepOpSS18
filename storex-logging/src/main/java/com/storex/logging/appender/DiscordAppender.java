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
 * BÀI TẬP 3: Custom Logback Appender bắn cảnh báo ERROR lên Discord Webhook
 * - Bất đồng bộ (Non-blocking)
 * - Chống đệ quy vô tận (Infinite Recursion Prevention)
 * - Kế thừa RequestId từ MDC (Bài tập 2)
 */
public class DiscordAppender extends AppenderBase<ILoggingEvent> {

    private String webhookUrl;
    private String applicationName = "StoreX-Backend";

    private HttpClient httpClient;
    private ExecutorService executorService;
    private final ThreadLocal<Boolean> isSending = ThreadLocal.withInitial(() -> false);

    @Override
    public void start() {
        this.executorService = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "discord-webhook-worker");
            t.setDaemon(true);
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
        if (eventObject == null || eventObject.getLevel().levelInt < Level.ERROR_INT) {
            return;
        }

        if (webhookUrl == null || webhookUrl.isBlank() || isSending.get()) {
            return;
        }

        try {
            isSending.set(true);

            String payloadJson = buildDiscordPayload(eventObject);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(webhookUrl))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofString(payloadJson))
                    .build();

            CompletableFuture<HttpResponse<String>> future = httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString());
            future.whenComplete((response, throwable) -> {
                if (throwable != null) {
                    addError("Gửi cảnh báo Discord thất bại: " + throwable.getMessage());
                } else if (response.statusCode() >= 400) {
                    addWarn("Discord Webhook trả về status: " + response.statusCode());
                }
            });

        } catch (Exception e) {
            addError("Lỗi khởi tạo bản tin Discord Alert: " + e.getMessage());
        } finally {
            isSending.set(false);
        }
    }

    private String buildDiscordPayload(ILoggingEvent event) {
        String message = escapeJson(event.getFormattedMessage());
        String logger = escapeJson(event.getLoggerName());
        String thread = escapeJson(event.getThreadName());
        String time = Instant.ofEpochMilli(event.getTimeStamp()).toString();

        Map<String, String> mdc = event.getMDCPropertyMap();
        String requestId = (mdc != null && mdc.containsKey("requestId")) ? mdc.get("requestId") : "N/A";

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

    public String getWebhookUrl() { return webhookUrl; }
    public void setWebhookUrl(String webhookUrl) { this.webhookUrl = webhookUrl; }
    public String getApplicationName() { return applicationName; }
    public void setApplicationName(String applicationName) { this.applicationName = applicationName; }
}
