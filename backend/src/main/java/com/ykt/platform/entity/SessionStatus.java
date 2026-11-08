package com.ykt.platform.entity;

/** 雨课堂会话状态，取值见接口契约第一节。 */
public enum SessionStatus {
    WAITING_SCAN, SCANNED, EXPIRED, CLOSED,
    /** VIP：扫码登录的雨课堂账号与绑定不符（或已被他人绑定），message 字段携带拒绝原因。 */
    REJECTED
}
