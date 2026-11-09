package com.ykt.platform.dto;

/** 修改用户名（登录名同步变更，改名后需用新用户名登录）。 */
public record ChangeUsernameRequest(String username) {
}
