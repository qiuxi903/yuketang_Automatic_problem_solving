package com.ykt.platform.dto;

import java.time.Instant;

/** 单日价格覆盖的一行（管理端）。priceFen 为该日期的解锁价（分）。 */
public record VipBatchPriceView(String batch, int priceFen, Instant updatedAt) {
}
