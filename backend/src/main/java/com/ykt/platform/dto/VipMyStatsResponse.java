package com.ykt.platform.dto;

/** 用户侧 VIP 统计（/api/vip/my-stats）：我的消费与解锁概况。 */
public record VipMyStatsResponse(
        long totalSpentFen,
        long paidOrders,
        int unlockedCount,
        int freeQuota,
        String monthCardState) {
}
