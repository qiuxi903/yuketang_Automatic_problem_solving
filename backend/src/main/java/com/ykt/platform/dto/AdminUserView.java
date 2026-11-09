package com.ykt.platform.dto;

import java.time.Instant;

/** 管理后台用户列表一行（含账号状态与最近登录信息）。 */
public record AdminUserView(
        Long id,
        String username,
        String role,
        long taskCount,
        long runningCount,
        Instant createdAt,
        boolean disabled,
        String lastLoginIp,
        String lastLoginLocation,
        Instant lastLoginAt,
        /** 累计充值（已支付订单实付合计，分） */
        long paidTotalFen,
        long paidOrders) {
}
