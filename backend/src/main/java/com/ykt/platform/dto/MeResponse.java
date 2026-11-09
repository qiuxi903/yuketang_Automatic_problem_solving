package com.ykt.platform.dto;

import java.time.Instant;

/** 当前登录用户信息（个人中心 / 顶栏头像）。 */
public record MeResponse(
        Long id,
        String username,
        String role,
        String avatar,
        Instant createdAt,
        String lastLoginIp,
        String lastLoginLocation,
        Instant lastLoginAt) {
}
