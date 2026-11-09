package com.ykt.platform.dto;

import java.time.Instant;

/** 到账记录池的一行（管理端透明化：哪些收款进来了、有没有被订单核销）。 */
public record VipPayNotificationView(
        Long id,
        String channel,
        int amountFen,
        String raw,
        Instant receivedAt,
        boolean consumed,
        String consumedByOrderNo) {
}
