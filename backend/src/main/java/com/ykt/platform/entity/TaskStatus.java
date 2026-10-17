package com.ykt.platform.entity;

/** 任务状态，取值见接口契约第一节。 */
public enum TaskStatus {
    /** 已创建，等待 Agent 领取。 */
    PENDING,
    /** Agent 正在执行（含扫码登录、作答）。 */
    RUNNING,
    /** 已扫码登录并回传账号作业清单，等待用户确认批次。 */
    WAITING_BATCH,
    PAUSED,
    /** 用户主动中断，任务不再继续执行（终端态）。 */
    CANCELLED,
    SUCCESS, FAILED
}
