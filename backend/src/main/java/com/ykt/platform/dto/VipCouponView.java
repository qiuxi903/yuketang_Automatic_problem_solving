package com.ykt.platform.dto;

import java.time.Instant;

/** 优惠码管理列表的一行。 */
public record VipCouponView(
        Long id,
        String code,
        String kind,
        Integer valuePercent,
        Integer quotaCount,
        String appliesTo,
        int maxUses,
        int perUserLimit,
        int usedCount,
        int remainingUses,
        boolean disabled,
        Instant expiresAt,
        Instant createdAt,
        String createdBy) {
}
