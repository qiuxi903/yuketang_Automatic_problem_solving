package com.ykt.platform.dto;

import java.time.Instant;

/** 权益行视图（管理端用户权益页）。 */
public record VipEntitlementView(
        Long id,
        Long userId,
        String username,
        String kind,
        String targetBatch,
        Instant expiresAt,
        boolean revoked,
        String sourceOrderNo,
        Instant createdAt) {
}
