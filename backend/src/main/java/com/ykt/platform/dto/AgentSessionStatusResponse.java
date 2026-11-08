package com.ykt.platform.dto;

/**
 * Agent 回传扫码状态后的响应：绑定校验拒绝时 sessionStatus=REJECTED、message 带理由，
 * Agent 据此中止任务（前端会话列表同步展示拒绝原因）。
 */
public record AgentSessionStatusResponse(boolean ok, String sessionStatus, String message) {

    public static AgentSessionStatusResponse of(String sessionStatus) {
        return new AgentSessionStatusResponse(true, sessionStatus, null);
    }
}
