package com.ykt.platform.dto;

import java.time.Instant;

/** 管理后台任务视图：跨用户的全量任务，附带归属用户名。 */
public record AdminTaskView(
        Long taskId,
        Long userId,
        String username,
        String batch,
        String mode,
        String status,
        int totalPaper,
        int donePaper,
        String currentPaper,
        Integer currentSeq,
        Integer currentTotal,
        String score,
        String message,
        Instant createdAt) {
}
