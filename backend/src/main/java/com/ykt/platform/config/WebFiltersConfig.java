package com.ykt.platform.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import jakarta.servlet.Filter;

/**
 * Servlet 级过滤器注册：顺序在 Spring Security 链（order -100）之后，
 * 只对「安全链放行的请求」做限流与 Agent 鉴权（401/静态资源不计）。
 */
@Configuration
public class WebFiltersConfig {

    @Bean
    public FilterRegistrationBean<Filter> agentTokenFilterRegistration(
            @Value("${ykt.agent-token:}") String token) {
        FilterRegistrationBean<Filter> reg = new FilterRegistrationBean<>(new AgentTokenFilter(token));
        reg.setOrder(-95);
        reg.addUrlPatterns("/api/agent/*", "/api/answer/*");
        return reg;
    }

    @Bean
    public FilterRegistrationBean<Filter> rateLimitFilterRegistration() {
        FilterRegistrationBean<Filter> reg = new FilterRegistrationBean<>(new RateLimitFilter());
        reg.setOrder(-90);
        reg.addUrlPatterns("/api/*");
        return reg;
    }
}
