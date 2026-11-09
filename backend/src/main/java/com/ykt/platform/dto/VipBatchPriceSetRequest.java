package com.ykt.platform.dto;

/** 设置某日期单日价的请求（upsert）。priceFen 单位分，需 ≥ 1。 */
public record VipBatchPriceSetRequest(String batch, Integer priceFen) {
}
