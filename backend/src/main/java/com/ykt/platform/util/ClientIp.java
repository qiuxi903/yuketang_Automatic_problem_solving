package com.ykt.platform.util;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 客户端真实 IP 解析（内网穿透/反代场景）。
 *
 * 解析优先级：X-Real-IP → X-Forwarded-For 第一段 → remoteAddr。
 * 链路上每层代理/隧道都可能追加 XFF，完整链存进登录日志供审计；展示值取第一段（最初客户端）。
 * 已知局限：客户端可伪造 XFF 首段（防爬/限流不受影响，只影响展示）。
 */
public final class ClientIp {

    private ClientIp() {
    }

    /** 展示/记录用的客户端 IP。 */
    public static String resolve(HttpServletRequest request) {
        String real = request.getHeader("X-Real-IP");
        if (real != null && !real.isBlank()) {
            return firstIp(real);
        }
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return firstIp(xff);
        }
        return trim(request.getRemoteAddr());
    }

    /** 完整转发链（审计用），没有代理头时返回 remoteAddr。 */
    public static String fullChain(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff + " | remote=" + trim(request.getRemoteAddr());
        }
        return trim(request.getRemoteAddr());
    }

    private static String firstIp(String header) {
        String first = header.split(",")[0].trim();
        return first.isEmpty() ? "unknown" : first;
    }

    private static String trim(String v) {
        return v == null || v.isBlank() ? "unknown" : v.trim();
    }
}
