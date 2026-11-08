package com.ykt.platform.dto;

import java.time.Instant;

/** 生效中的折扣活动（付费页/下单响应共用）。 */
public record VipActivityView(
        Long id,
        String name,
        int discountPercent,
        String appliesTo,
        Instant startsAt,
        Instant endsAt,
        boolean enabled) {
}
