package com.ykt.platform.dto;

import java.util.List;
import java.util.Map;

/** 管理后台概览统计：用户、任务、试卷、会话的总量与分布。 */
public record AdminOverviewResponse(
        long userCount,
        long adminCount,
        long taskCount,
        Map<String, Long> taskStatus,
        long paperCount,
        long questionCount,
        long batchCount,
        long sessionCount,
        Map<String, Long> sessionStatus,
        List<BatchBrief> batches) {

    /** 单个批次的试卷与题量摘要。 */
    public record BatchBrief(String batch, long papers, long questions) {
    }
}
