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
 * BÀI TẬP 2: Gắn RequestId vào MDC (Mapped Diagnostic Context) và dọn dẹp bằng khối finally.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String MDC_KEY_REQUEST_ID = "requestId";
    public static final String HEADER_REQUEST_ID = "X-Request-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        // 1. Nhận RequestId từ Header Gateway hoặc sinh mới UUID
        String requestId = request.getHeader(HEADER_REQUEST_ID);
        if (!StringUtils.hasText(requestId)) {
            requestId = UUID.randomUUID().toString();
        }

        try {
            // 2. Put vào MDC để Logback tự động in vào mọi dòng log
            MDC.put(MDC_KEY_REQUEST_ID, requestId);

            // 3. Trả về Response Header cho Client
            response.setHeader(HEADER_REQUEST_ID, requestId);

            // 4. Tiếp tục thực thi chuỗi request
            filterChain.doFilter(request, response);

        } finally {
            // 5. Ngăn chặn ThreadLocal Memory Leak và Value Bleed trên Tomcat Thread Pool
            MDC.remove(MDC_KEY_REQUEST_ID);
        }
    }
}
