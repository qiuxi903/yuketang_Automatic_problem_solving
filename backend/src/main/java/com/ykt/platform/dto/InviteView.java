package com.ykt.platform.dto;

import java.time.Instant;

/** 邀请码管理列表的一行。 */
public record InviteView(
        String code,
        boolean used,
        String usedBy,
        Instant usedAt,
        boolean disabled,
        Instant createdAt) {
}
