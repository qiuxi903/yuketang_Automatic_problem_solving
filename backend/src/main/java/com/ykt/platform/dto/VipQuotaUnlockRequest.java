package com.ykt.platform.dto;

/** 消耗 1 次免费额度解锁指定批次。 */
public record VipQuotaUnlockRequest(String targetBatch) {
}
