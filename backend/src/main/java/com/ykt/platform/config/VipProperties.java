package com.ykt.platform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * VIP 功能配置（ykt.vip.*）。
 *
 * @param enforce               付费闸门总开关：false 时 VIP 分支 behaves like 免费版（所有批次放行）
 * @param bindRequired          雨课堂账号绑定开关：false 时扫码不再做 1:1 绑定校验
 * @param freeQuotaOnRegister   新用户注册赠送的免费解锁次数（存量用户启动时也按此补建账户）
 * @param xunhuAppid            虎皮椒 appid（留 local.yml，不入库不入 git）
 * @param xunhuSecret           虎皮椒 appsecret（同上）
 */
@ConfigurationProperties(prefix = "ykt.vip")
public record VipProperties(
        boolean enforce,
        boolean bindRequired,
        int freeQuotaOnRegister,
        String xunhuAppid,
        String xunhuSecret) {
}
