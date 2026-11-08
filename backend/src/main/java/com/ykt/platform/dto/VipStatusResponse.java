package com.ykt.platform.dto;

import java.time.Instant;
import java.util.List;

/** 当前用户的 VIP 权益总览（/api/vip/status）。 */
public record VipStatusResponse(
        boolean enforce,
        Instant monthCardExpiresAt,
        boolean monthCardActive,
        int freeQuota,
        List<String> unlockedBatches,
        String boundYktName,
        String payChannel,
        String payNote) {
}
