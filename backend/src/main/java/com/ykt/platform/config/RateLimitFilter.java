package com.ykt.platform.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 公网暴露面的 IP 限流（内存滑动窗口，按 前缀+方法 分桶）：
 * 注册/登录防爆破，下单/建任务/兑换防灌水。超限返回 429 JSON。
 * 单实例内存实现即可（本地 H2 单后端部署）；阈值按正常用户余量放大，宁松勿误伤。
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    /** 前缀 → [最大次数, 窗口毫秒]；只对写方法生效。 */
    private static final Map<String, int[]> RULES = Map.of(
            "/api/auth/", new int[]{20, 60_000},
            "/api/vip/orders", new int[]{20, 60_000},
            "/api/vip/redeem", new int[]{10, 60_000},
            "/api/vip/unlock-quota", new int[]{10, 60_000},
            "/api/tasks", new int[]{30, 60_000},
            "/api/sessions", new int[]{20, 60_000});

    /** 读接口（防爬）规则：/api/answer|/api/community GET 60 次/分钟。 */
    private static final int[] READ_RULE = {60, 60_000};

    private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String method = request.getMethod();
        String uri = request.getRequestURI();
        boolean write = "POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method)
                || "DELETE".equalsIgnoreCase(method);
        if (write) {
            for (String prefix : RULES.keySet()) {
                if (uri.startsWith(prefix)) {
                    return false;
                }
            }
            return true;
        }
        // 防爬：答案中心读接口也限流（GET 60 次/分钟/IP，正常使用远达不到）
        return !uri.startsWith("/api/answer/") && !uri.startsWith("/api/community");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String uri = request.getRequestURI();
        int[] rule = RULES.entrySet().stream()
                .filter(e -> uri.startsWith(e.getKey()))
                .map(Map.Entry::getValue)
                .findFirst().orElse(null);
        if (rule == null) {
            rule = READ_RULE;
        }
        long now = System.currentTimeMillis();
        String key = clientIp(request) + "|" + uri;
        Deque<Long> window = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (window) {
            while (!window.isEmpty() && now - window.peekFirst() > rule[1]) {
                window.pollFirst();
            }
            if (window.size() >= rule[0]) {
                log.warn("限流触发：{} {}（{} 分钟内第 {} 次）", request.getMethod(), uri, rule[1] / 60_000, window.size());
                response.setStatus(429);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"code\":429,\"message\":\"请求过于频繁，请稍后再试\"}");
                return;
            }
            window.addLast(now);
        }
        chain.doFilter(request, response);
    }

    /** 反代/内网穿透下优先取 X-Forwarded-For 的首个 IP。 */
    private static String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
