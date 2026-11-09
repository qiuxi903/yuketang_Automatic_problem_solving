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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * Agent 内部接口鉴权：/api/agent/** 与 /api/answer/** 必须携带 X-Agent-Token。
 *
 * 令牌来源（按优先级）：
 * 1. ykt.agent-token 配置 / YKT_AGENT_TOKEN 环境变量（分离部署用）
 * 2. 自动生成：随机 48 位十六进制写入 data/agent-token 文件（同机部署零配置——
 *    agent 启动时自动读取同文件，双方免配置即安全；文件在 data/ 下不会对外暴露）
 */
public class AgentTokenFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AgentTokenFilter.class);

    private final String token;

    public AgentTokenFilter(@Value("${ykt.agent-token:}") String configured,
                            @Value("${ykt.agent-token-file:./data/agent-token}") String tokenFile) {
        String t = configured == null ? "" : configured.trim();
        if (t.isEmpty()) {
            t = loadOrGenerate(tokenFile);
        }
        this.token = t;
        log.info("Agent 接口鉴权已启用（令牌来源：{}）", configured.isEmpty() ? "自动生成 data/agent-token" : "配置");
    }

    private static String loadOrGenerate(String file) {
        Path path = Paths.get(file);
        try {
            if (Files.exists(path)) {
                String saved = Files.readString(path, StandardCharsets.UTF_8).trim();
                if (!saved.isEmpty()) {
                    return saved;
                }
            }
            byte[] bytes = new byte[24];
            new SecureRandom().nextBytes(bytes);
            String generated = HexFormat.of().formatHex(bytes);
            Files.createDirectories(path.getParent());
            Files.writeString(path, generated, StandardCharsets.UTF_8);
            log.info("已自动生成 Agent 接口令牌：{}", path);
            return generated;
        } catch (IOException e) {
            log.error("Agent 令牌文件读写失败 {}：{}（内部接口将不可用）", path, e.getMessage());
            // 返回不可能匹配的随机串：宁可拒绝也不裸奔
            return HexFormat.of().formatHex(new SecureRandom().generateSeed(24));
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        // 浏览器前端要读取的两个只读端点（二维码/实时帧）放行：登录用户经前端轮询获取，
        // 无敏感枚举面；其余（含任务领取/状态回传/兜底答题）一律需令牌
        if ("GET".equalsIgnoreCase(request.getMethod())
                && (uri.endsWith("/qrcode") || uri.endsWith("/frame"))) {
            return true;
        }
        return !uri.startsWith("/api/agent/") && !uri.startsWith("/api/answer/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String given = request.getHeader("X-Agent-Token");
        if (token.equals(given)) {
            chain.doFilter(request, response);
            return;
        }
        log.warn("Agent 接口鉴权失败：{} {}（缺少或错误的 X-Agent-Token）", request.getMethod(), request.getRequestURI());
        response.setStatus(403);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":403,\"message\":\"Agent 接口鉴权失败\"}");
    }
}
