package com.ykt.platform.security;

import com.ykt.platform.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Service
public class JwtService {

    private final SecretKey key;
    private final long expireMinutes;

    public JwtService(JwtProperties properties) {
        this.key = Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));
        this.expireMinutes = properties.expireMinutes();
    }

    public String generate(Long userId, String username, String role) {
        Date now = new Date();
        Date exp = new Date(now.getTime() + expireMinutes * 60_000L);
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("username", username)
                .claim("role", role == null ? "USER" : role)
                .issuedAt(now)
                .expiration(exp)
                .signWith(key)
                .compact();
    }

    public AuthUser parse(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        Long userId = Long.valueOf(claims.getSubject());
        String username = claims.get("username", String.class);
        String role = claims.get("role", String.class);
        return new AuthUser(userId, username, role == null ? "USER" : role);
    }
}
