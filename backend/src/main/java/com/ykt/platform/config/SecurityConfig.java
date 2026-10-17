package com.ykt.platform.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ykt.platform.security.JwtAuthFilter;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, JwtAuthFilter jwtAuthFilter,
                                           ObjectMapper objectMapper) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // 错误转发必须放行，否则任何 404/500 都会被鉴权拦截成无响应体的 403，掩盖真实错误
                        .dispatcherTypeMatchers(DispatcherType.ERROR, DispatcherType.FORWARD).permitAll()
                        .requestMatchers("/error").permitAll()
                        .requestMatchers("/api/auth/**", "/api/agent/**", "/api/answer/**", "/ws/**", "/h2-console/**").permitAll()
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        // 管理员后台接口仅 ADMIN 可访问
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                // 鉴权失败返回 JSON 且区分 401/403：未登录→401（前端据此跳登录页），已登录无权限→403
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, authException) ->
                                writeError(objectMapper, response, HttpServletResponse.SC_UNAUTHORIZED,
                                        "未登录或登录已过期，请重新登录", request.getRequestURI()))
                        .accessDeniedHandler((request, response, deniedException) ->
                                writeError(objectMapper, response, HttpServletResponse.SC_FORBIDDEN,
                                        "无权访问该资源", request.getRequestURI())))
                // h2-console 需要同源 iframe
                .headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()))
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /** 以统一 JSON 结构输出鉴权错误，避免出现无响应体的 401/403 导致前端只能提示「Request failed」。 */
    private static void writeError(ObjectMapper objectMapper, HttpServletResponse response,
                                   int status, String message, String path) throws java.io.IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        objectMapper.writeValue(response.getWriter(),
                java.util.Map.of("code", status, "message", message, "path", path));
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        // 内网穿透对外开放：访问来源（域名/端口）会随隧道变化，放行任意来源；
        // 接口鉴权靠 JWT（存于各用户浏览器，第三方站点拿不到），CSRF 已关闭，无被跨站伪造风险
        config.setAllowedOriginPatterns(List.of("*"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
