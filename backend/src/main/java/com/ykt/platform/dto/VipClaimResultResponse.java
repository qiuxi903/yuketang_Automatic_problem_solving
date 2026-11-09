package com.ykt.platform.dto;

/** 「我已支付，核实到账」的结果：verified=true 表示核实到符合金额的到账并已开通。 */
public record VipClaimResultResponse(boolean verified, String message) {
}
