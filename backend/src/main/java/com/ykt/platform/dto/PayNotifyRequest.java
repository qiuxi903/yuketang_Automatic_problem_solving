package com.ykt.platform.dto;

/**
 * 免签监听回调请求（pay-listener / 后台快速核销共用）。
 *
 * @param channel   wechat / alipay（仅记录来源）
 * @param amountFen 收款金额（分，必须精确到分）
 * @param raw       原始通知文本（如「微信支付收款3.47元」），用于审计与人工核对
 */
public record PayNotifyRequest(String channel, Integer amountFen, String raw) {
}
