package com.ykt.platform.dto;

import java.time.Instant;
import java.util.List;

/** 任务快照，字段与接口契约第五节的 Task 结构一一对应。 */
public record TaskSnapshot(
        Long taskId,
        String batch,
        String mode,
        String status,
        Long sessionId,
        int totalPaper,
        int donePaper,
        String currentPaper,
        Integer currentSeq,
        Integer currentTotal,
        String score,
        List<TaskItemSnapshot> items,
        String message,
        String course,
        List<AccountPaperView> papers,
        /** 重做任务只处理这些卷子（普通任务为 null）。 */
        List<String> redoPapers,
        /** 同账号续做提示：优先派给已登录该雨课堂账号的执行工人。 */
        String reuseYkt,
        /** 任务创建时间；「我的任务」列表按此展示，刷新后据此恢复最近一次任务。 */
        Instant createdAt) {
}
