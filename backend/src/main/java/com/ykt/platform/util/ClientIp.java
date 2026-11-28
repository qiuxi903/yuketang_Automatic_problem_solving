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

    /** 展示/记录用的客户端 IP。
     *
     * 链路上游（内网穿透 TCP 转发等）没透传真实来源时，链路 IP 只会是回环/内网地址——
     * 此时退回「浏览器自报的公网出口 IP」（X-Client-Public-IP：用户设备直连公网探测服务所得，
     * 随请求带上）。自报可伪造，仅展示用；限流等安全判定不用它。 */
    public static String resolve(HttpServletRequest request) {
        String chain = chainIp(request);
        if (isPrivateOrLoopback(chain)) {
            String self = trim(request.getHeader("X-Client-Public-IP"));
            if (!"unknown".equals(self)) {
                return self;
            }
        }
        return chain;
    }

    /** 转发链解析出的来源 IP（审计口径，不掺自报值）。 */
    private static String chainIp(HttpServletRequest request) {
        String real = request.getHeader("X-Real-IP");
        if (real != null && !real.isBlank()) {
            return firstIp(real);
        }
        // Cloudflare Tunnel 等穿透只会带 CF-Connecting-IP
        String cf = request.getHeader("CF-Connecting-IP");
        if (cf != null && !cf.isBlank()) {
            return firstIp(cf);
        }
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return firstIp(xff);
        }
        return trim(request.getRemoteAddr());
    }

    /** 回环/内网地址判定（含 IPv4-mapped 的 ::ffff:127.0.0.1 形式）——这类值说明上游没透传真实来源。 */
    static boolean isPrivateOrLoopback(String ip) {
        if (ip == null || ip.isBlank()) {
            return true;
        }
        String v = ip.toLowerCase().replace("::ffff:", "");
        if (v.equals("::1") || v.equals("0.0.0.0") || v.equals("unknown") || v.startsWith("127.")) {
            return true;
        }
        if (v.startsWith("10.") || v.startsWith("192.168.") || v.startsWith("169.254.")) {
            return true;
        }
        if (v.startsWith("172.")) {
            String[] parts = v.split("\\.");
            if (parts.length > 1) {
                try {
                    int second = Integer.parseInt(parts[1]);
                    if (second >= 16 && second <= 31) {
                        return true;
                    }
                } catch (NumberFormatException ignored) {
                    // 非法段按公网处理
                }
            }
        }
        return false;
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
