package com.storex.logging.filter;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;

/**
 * Filter chặn mọi HTTP Request đến ứng dụng để:
 * 1. Khởi tạo hoặc tiếp nhận RequestId/TraceId.
 * 2. Đưa RequestId vào MDC (Mapped Diagnostic Context) của SLF4J/Logback.
 * 3. Gắn RequestId vào HTTP Response Header để Client/Dev dễ dàng tra cứu.
 * 4. Dọn dẹp sạch sẽ MDC trong khối finally để ngăn ngừa rò rỉ bộ nhớ (ThreadLocal Leak).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE) // Đảm bảo filter chạy đầu tiên trong chuỗi FilterChain
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String MDC_KEY_REQUEST_ID = "requestId";
    public static final String HEADER_REQUEST_ID = "X-Request-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        // 1. Kiểm tra xem Client hoặc API Gateway có truyền sẵn Header X-Request-Id không
        String requestId = request.getHeader(HEADER_REQUEST_ID);
        if (!StringUtils.hasText(requestId)) {
            // Nếu không có, sinh mới một UUID duy nhất cho request này
            requestId = UUID.randomUUID().toString();
        }

        try {
            // 2. ĐÃ SỬA: Đưa requestId vào Mapped Diagnostic Context (MDC)
            // Nhờ đó mọi lệnh logger.info, logger.error trong toàn bộ luồng xử lý đều tự động có requestId này
            MDC.put(MDC_KEY_REQUEST_ID, requestId);

            // 3. Trả requestId về trong Response Header để Client có mã tra cứu khi phát sinh sự cố
            response.setHeader(HEADER_REQUEST_ID, requestId);

            // 4. Cho phép request tiếp tục đi vào Controller và Service
            filterChain.doFilter(request, response);

        } finally {
            // 5. BẪY KỸ THUẬT CỰC KỲ QUAN TRỌNG:
            // MDC sử dụng cơ chế ThreadLocal bên dưới. Tomcat tái sử dụng Thread Pool.
            // Nếu không dọn dẹp trong khối finally, Thread khi nhận request mới sẽ bị dính
            // requestId của user cũ (Value Bleed) và gây rò rỉ bộ nhớ (Memory Leak)!
            MDC.remove(MDC_KEY_REQUEST_ID);
            // Hoặc có thể dùng: MDC.clear();
        }
    }
}
