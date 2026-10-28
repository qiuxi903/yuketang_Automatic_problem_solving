package com.ykt.platform.dto;

/**
 * 生成邀请码请求。
 *
 * @param count   生成数量
 * @param maxUses 每个码允许注册的次数；0 = 不限；null = 一码一用
 */
public record InviteCreateRequest(int count, Integer maxUses) {
}
