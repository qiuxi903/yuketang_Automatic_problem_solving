package com.ykt.platform.vip;

/**
 * 付费闸门拒绝：尝试解锁未付费批次时抛出，
 * 由 {@link VipExceptionHandler} 转成 HTTP 402 + 结构化 JSON（前端拦截器据此跳付费页）。
 */
public class VipRequiredException extends RuntimeException {

    private final String batch;

    public VipRequiredException(String batch) {
        super("该日期尚未解锁，需要付费后才能做题");
        this.batch = batch;
    }

    public String getBatch() {
        return batch;
    }
}
