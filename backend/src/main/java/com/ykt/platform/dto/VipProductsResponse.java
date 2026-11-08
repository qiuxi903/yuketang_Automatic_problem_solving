package com.ykt.platform.dto;

import java.time.Instant;
import java.util.List;

/** 付费页商品数据（价格/活动折扣/各批次解锁态/收款方式），/api/vip/products。 */
public record VipProductsResponse(
        int datePassPriceFen,
        int monthPriceFen,
        int monthDays,
        String payChannel,
        String payNote,
        boolean hasQrWechat,
        boolean hasQrAlipay,
        VipActivityView activity,
        List<VipBatchView> batches) {

    /** 批次（日期）行：unlocked 表示当前用户已可免费做题。 */
    public record VipBatchView(String batch, long papersCount, boolean unlocked) {
    }
}
