package com.ykt.platform.dto;

import java.time.Instant;

/** 订单视图：用户查自己的订单；管理员列表时 username 非空。 */
public record VipOrderView(
        Long id,
        String orderNo,
        Long userId,
        String username,
        String kind,
        String targetBatch,
        int originalFen,
        int discountFen,
        int suffixFen,
        int payableFen,
        String status,
        String payChannel,
        String couponCode,
        String remark,
        String adminRemark,
        Instant claimedAt,
        Instant paidAt,
        Instant expiresAt,
        Instant createdAt,
        String activityName) {
}
