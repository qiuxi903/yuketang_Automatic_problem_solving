package com.ykt.platform.dto;

/**
 * 兑换码核销请求。
 * DATE_PASS 码必须带 targetBatch（解锁哪个日期由用户自己挑）；
 * MONTH_PASS / QUOTA 码 targetBatch 忽略。
 */
public record VipRedeemRequest(String code, String targetBatch) {
}
