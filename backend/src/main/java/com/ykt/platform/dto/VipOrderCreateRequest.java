package com.ykt.platform.dto;

/**
 * 创建订单请求。
 *
 * @param kind       DATE_PASS / MONTH_CARD
 * @param targetBatch DATE_PASS 必填（要解锁的批次日期）
 * @param couponCode  可选折扣码
 * @param remark      用户留言（人工渠道附支付凭证说明）
 */
public record VipOrderCreateRequest(String kind, String targetBatch, String couponCode, String remark) {
}
