package com.ykt.platform.dto;

import java.time.Instant;
import java.util.List;

/**
 * 邀请码管理列表的一行。
 *
 * @param maxUses 允许注册的最大次数；0 = 不限
 */
public record InviteView(
        String code,
        boolean used,
        String usedBy,
        Instant usedAt,
        boolean disabled,
        Instant createdAt,
        int maxUses,
        int usedCount,
        List<InviteUseView> uses) {
}
