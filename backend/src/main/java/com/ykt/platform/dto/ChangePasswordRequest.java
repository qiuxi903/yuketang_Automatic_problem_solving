package com.ykt.platform.dto;

/** 本人修改密码：需验证旧密码；成功后其它设备登录态失效。 */
public record ChangePasswordRequest(String oldPassword, String newPassword) {
}
