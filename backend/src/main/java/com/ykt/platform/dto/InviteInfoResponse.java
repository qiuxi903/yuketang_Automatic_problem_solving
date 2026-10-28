package com.ykt.platform.dto;

/**
 * 邀请码公开查询结果（注册页校验用，不暴露管理信息）。
 *
 * @param valid     邀请码存在且可用（未禁用、未用尽）
 * @param disabled  是否已被禁用
 * @param exhausted 是否已用尽
 * @param remaining 剩余可用次数；不限次数为 -1
 * @param maxUses   允许注册的最大次数；0 = 不限
 */
public record InviteInfoResponse(
        boolean valid,
        boolean disabled,
        boolean exhausted,
        int remaining,
        int maxUses) {
}
