package com.ykt.platform.dto;

import java.time.Instant;

/** 绑定列表的一行（管理端）。 */
public record VipBindingView(
        Long id,
        Long userId,
        String username,
        String yktName,
        boolean autoBound,
        Instant boundAt) {
}
