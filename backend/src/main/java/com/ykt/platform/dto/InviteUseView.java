package com.ykt.platform.dto;

import java.time.Instant;

/** 邀请码的一次使用记录（谁、什么时候注册的）。 */
public record InviteUseView(String username, Instant usedAt) {
}
