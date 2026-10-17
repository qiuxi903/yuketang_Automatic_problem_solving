package com.ykt.platform.dto;

import java.time.Instant;

/** 管理后台用户视图：账号信息 + 该用户的任务数量。 */
public record AdminUserView(
        Long id,
        String username,
        String role,
        long taskCount,
        long runningCount,
        Instant createdAt) {
}
