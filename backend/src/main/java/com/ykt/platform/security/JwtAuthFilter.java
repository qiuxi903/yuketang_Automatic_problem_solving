package com.ykt.platform.security;

import com.ykt.platform.entity.User;
import com.ykt.platform.repository.UserRepository;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserRepository userRepository;
    /** passwordUpdatedAt 缓存 30s：避免每请求查库，改密踢下线最多延迟 30s 生效 */
    private final java.util.concurrent.ConcurrentHashMap<Long, java.util.Map.Entry<java.time.Instant, Long>> pwdCache
            = new java.util.concurrent.ConcurrentHashMap<>();

    public JwtAuthFilter(JwtService jwtService, UserRepository userRepository) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7);
            try {
                AuthUser user = jwtService.parse(token);
                // 改密踢下线：token 签发时间早于上次改密 → 视为过期
                if (!tokenAlive(user, token)) {
                    SecurityContextHolder.clearContext();
                    filterChain.doFilter(request, response);
                    return;
                }
                // 改名后 token 里的旧用户名 30 秒内刷新为库中最新
                user = withFreshUsername(user);
                String role = user.role() == null || user.role().isBlank() ? "USER" : user.role();
                var authentication = new UsernamePasswordAuthenticationToken(
                        user, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (JwtException | IllegalArgumentException ex) {
                // 令牌无效时不设置认证，由后续授权规则返回 401/403
                SecurityContextHolder.clearContext();
            }
        }
        filterChain.doFilter(request, response);
    }

    /** 缓存：userId -> (最新用户名, 写入时间)；随 passwordUpdatedAt 缓存一起 30s 刷新。 */
    private final java.util.concurrent.ConcurrentHashMap<Long, java.util.Map.Entry<String, Long>> nameCache
            = new java.util.concurrent.ConcurrentHashMap<>();

    /** 用库中最新用户名覆盖 token 里的旧名（30s 缓存，无库记录时原样返回）。 */
    private AuthUser withFreshUsername(AuthUser user) {
        try {
            long now = System.currentTimeMillis();
            java.util.Map.Entry<String, Long> cached = nameCache.get(user.id());
            String username;
            if (cached != null && now - cached.getValue() < 30_000) {
                username = cached.getKey();
            } else {
                username = userRepository.findById(user.id())
                        .map(User::getUsername)
                        .orElse(user.username());
                nameCache.put(user.id(), java.util.Map.entry(username, now));
            }
            if (!username.equals(user.username())) {
                return new AuthUser(user.id(), username, user.role());
            }
        } catch (Exception ignored) {
        }
        return user;
    }

    /** token 是否仍有效（签发时间晚于上次改密；passwordUpdatedAt 为空 = 未改过密，恒有效）。 */
    private boolean tokenAlive(AuthUser user, String token) {
        try {
            java.util.Map.Entry<java.time.Instant, Long> cached = pwdCache.get(user.id());
            long now = System.currentTimeMillis();
            java.time.Instant pwdAt;
            if (cached != null && now - cached.getValue() < 30_000) {
                pwdAt = cached.getKey();
            } else {
                pwdAt = userRepository.findById(user.id())
                        .map(User::getPasswordUpdatedAt)
                        .orElse(null);
                pwdCache.put(user.id(), java.util.Map.entry(pwdAt == null ? java.time.Instant.EPOCH : pwdAt, now));
            }
            if (pwdAt == null || pwdAt.equals(java.time.Instant.EPOCH)) {
                return true;
            }
            java.time.Instant issuedAt = jwtService.issuedAt(token);
            return issuedAt == null || issuedAt.isAfter(pwdAt.minusSeconds(1));
        } catch (Exception ex) {
            return true;
        }
    }
}
