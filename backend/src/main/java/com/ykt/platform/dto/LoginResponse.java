package com.ykt.platform.dto;

/** 登录出参：role 用于前端区分普通用户与管理员（USER / ADMIN）。 */
public record LoginResponse(String token, String username, String role) {
}
