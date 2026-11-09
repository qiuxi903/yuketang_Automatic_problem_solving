package com.ykt.platform.vip;

/**
 * 付费闸门拒绝：尝试解锁未付费批次时抛出，
 * 由 {@link VipExceptionHandler} 转成 HTTP 402 + 结构化 JSON（前端拦截器据此跳付费页）。
 */
public class VipRequiredException extends RuntimeException {

    private final String batch;

    public VipRequiredException(String batch) {
        super(batch == null
                ? "自动做题需要先开通：解锁某个日期（¥3 起）或开通月卡（¥15）"
                : "日期 " + batch + " 尚未解锁：解锁后才能自动做题");
        this.batch = batch;
    }

    public String getBatch() {
        return batch;
    }
}
