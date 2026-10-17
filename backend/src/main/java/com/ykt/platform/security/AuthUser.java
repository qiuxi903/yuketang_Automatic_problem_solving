package com.ykt.platform.security;

/** 认证主体：平台账号 id、用户名与角色（USER / ADMIN）。 */
public record AuthUser(Long id, String username, String role) {
}
