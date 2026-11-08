package com.ykt.platform.dto;

/**
 * 批量生成优惠码请求。
 *
 * @param count        生成数量（1~50）
 * @param kind         DISCOUNT / DATE_PASS / MONTH_PASS / QUOTA
 * @param valuePercent 折扣码：折扣百分数（80 = 8 折）
 * @param quotaCount   额度码：赠送次数
 * @param appliesTo    折扣码适用商品：DATE / MONTH / ALL
 * @param maxUses      每码最大使用次数；0 = 不限；null = 1
 * @param perUserLimit 单用户最多使用次数；null = 1
 * @param expiresDays  有效天数；null = 永不过期
 */
public record VipCouponCreateRequest(
        int count,
        String kind,
        Integer valuePercent,
        Integer quotaCount,
        String appliesTo,
        Integer maxUses,
        Integer perUserLimit,
        Integer expiresDays) {
}
