package com.carbon.common.web.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.security.SecureRandom;

/**
 * 链路 id 过滤器：每个请求生成/透传一个 {@code traceId}，写进 MDC 与响应头。
 *
 * <p>为什么这个 id 很重要：平台不做登录，出问题时运维手上只有「哪一次调用失败了」，
 * {@code traceId} 是把 {@code invoke_log}、应用日志、三方请求三者串起来的唯一线索。
 * 网关侧还会把它回给业务方（{@code GwResult.traceId}）。
 *
 * <p>透传规则：请求头带了 {@code X-Trace-Id} 就用它（网关 → 内部服务串联），
 * 但长度限制在 64 位、只允许 {@code [0-9a-zA-Z-]}，避免被塞进日志注入。
 *
 * @author carbon
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Trace-Id";
    public static final String MDC_KEY = "traceId";

    private static final int LENGTH = 12;
    private static final char[] ALPHABET = "0123456789abcdef".toCharArray();
    private final SecureRandom random = new SecureRandom();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String traceId = sanitize(request.getHeader(HEADER));
        if (traceId == null) {
            traceId = generate();
        }
        MDC.put(MDC_KEY, traceId);
        response.setHeader(HEADER, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            // 必须清：Tomcat 线程是复用的，残留会污染下一个请求的日志
            MDC.remove(MDC_KEY);
        }
    }

    private String generate() {
        char[] out = new char[LENGTH];
        for (int i = 0; i < LENGTH; i++) {
            out[i] = ALPHABET[random.nextInt(ALPHABET.length)];
        }
        return new String(out);
    }

    private String sanitize(String incoming) {
        if (incoming == null) {
            return null;
        }
        String trimmed = incoming.trim();
        if (trimmed.isEmpty() || trimmed.length() > 64) {
            return null;
        }
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            boolean ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z') || c == '-';
            if (!ok) {
                return null;
            }
        }
        return trimmed;
    }
}
