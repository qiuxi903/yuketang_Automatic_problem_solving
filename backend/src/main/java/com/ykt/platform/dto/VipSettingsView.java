package com.ykt.platform.dto;

import java.time.Instant;

/** 管理端 VIP 设置读取视图（监听令牌仅在管理端下发）。 */
public record VipSettingsView(
        int datePassPriceFen,
        int monthPriceFen,
        int monthDays,
        String payChannel,
        boolean hasQrWechat,
        boolean hasQrAlipay,
        String payNote,
        String listenToken) {
}
