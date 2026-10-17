package com.ykt.platform.dto;

/** Agent 轮询批次决策：batch 非空表示可以继续作答，mode 为登录后确认的作答模式。 */
public record BatchDecisionResponse(String status, String batch, String mode) {
}
