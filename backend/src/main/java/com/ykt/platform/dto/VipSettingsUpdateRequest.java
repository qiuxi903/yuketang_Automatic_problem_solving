package com.ykt.platform.dto;

/**
 * 管理端更新 VIP 设置。金额单位分；收款码传 null 表示不修改，传空串 "" 表示清除。
 *
 * @param datePassPriceFen 单日解锁价（分）
 * @param monthPriceFen    月卡价（分）
 * @param monthDays        月卡天数
 * @param payChannel       LISTEN / MANUAL / XUNHU
 * @param payQrWechat      微信收款码（base64 dataURL；null=不改，""=清除）
 * @param payQrAlipay      支付宝收款码（同上）
 * @param payNote          支付说明
 */
public record VipSettingsUpdateRequest(
        Integer datePassPriceFen,
        Integer monthPriceFen,
        Integer monthDays,
        String payChannel,
        String payQrWechat,
        String payQrAlipay,
        String payNote) {
}
