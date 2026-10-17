package com.ykt.platform.dto;

import java.time.Instant;

/** 会话详情：供前端展示扫码状态、二维码、批次/模式与截止时间。 */
public record SessionDetailResponse(
        Long sessionId,
        String status,
        String qrcode,
        String yktName,
        String batch,
        String mode,
        Instant expireAt,
        Instant createdAt) {
}
