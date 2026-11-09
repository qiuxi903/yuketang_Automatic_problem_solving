package com.ykt.platform.dto;

import java.util.List;

/**
 * VIP 运营统计（/api/admin/vip/stats）。
 *
 * @param totalRevenueFen    累计收入（已支付订单实付合计）
 * @param todayRevenueFen    今日收入（本地时区自然日）
 * @param dailyRevenue       近 7 日收入（含今日，升序；无单日为 0）
 * @param paidOrders         已支付订单数
 * @param pendingOrders      待支付订单数（未过期）
 * @param cancelledOrders    已取消订单数
 * @param rejectedOrders     已拒单订单数
 * @param monthActiveUsers   月卡在期用户数
 * @param dateEntitlements   单日解锁权益数（人次）
 * @param freeQuotaTotal     用户剩余免费额度总量
 * @param couponsActive      可用优惠码数（未禁用未用尽未过期）
 * @param couponsUsed        优惠码累计核销次数
 * @param paidUsers          付费用户数（有已支付订单的去重人数）
 * @param totalUsers         平台注册用户总数（算转化率用）
 * @param channelOrders      各收款渠道的已支付订单数（LISTEN/MANUAL/XUNHU）
 */
public record VipStatsResponse(
        long totalRevenueFen,
        long todayRevenueFen,
        List<DailyRevenue> dailyRevenue,
        long paidOrders,
        long pendingOrders,
        long cancelledOrders,
        long rejectedOrders,
        long monthActiveUsers,
        long dateEntitlements,
        long freeQuotaTotal,
        long couponsActive,
        long couponsUsed,
        long paidUsers,
        long totalUsers,
        java.util.Map<String, Long> channelOrders) {

    /** 单日收入行。 */
    public record DailyRevenue(String date, long revenueFen, long orders) {
    }
}
