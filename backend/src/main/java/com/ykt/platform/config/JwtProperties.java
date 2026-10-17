package com.ykt.platform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ykt.jwt")
public record JwtProperties(String secret, long expireMinutes) {
}
