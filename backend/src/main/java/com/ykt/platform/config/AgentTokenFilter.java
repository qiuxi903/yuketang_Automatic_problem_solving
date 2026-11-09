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

/**
 * Agent 内部接口鉴权（补 P1 债务）：/api/agent/** 与 /api/answer/** 只允许携带
 * X-Agent-Token: <ykt.agent-token> 的调用方访问。令牌未配置（空）时不启用，行为与主分支一致。
 * 部署：backend 与 agent 两侧同时设置 YKT_AGENT_TOKEN（或 backend 的 local.yml），
 * 本地默认关闭；公网暴露时必须配置。
 */
public class AgentTokenFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AgentTokenFilter.class);

    private final String token;

    public AgentTokenFilter(@Value("${ykt.agent-token:}") String token) {
        this.token = token == null ? "" : token.trim();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (token.isEmpty()) {
            return true;
        }
        String uri = request.getRequestURI();
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
