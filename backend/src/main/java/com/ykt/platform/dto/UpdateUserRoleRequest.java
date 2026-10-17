package com.ykt.platform.dto;

/** 管理员变更用户角色：role 仅接受 USER / ADMIN。 */
public record UpdateUserRoleRequest(String role) {
}
