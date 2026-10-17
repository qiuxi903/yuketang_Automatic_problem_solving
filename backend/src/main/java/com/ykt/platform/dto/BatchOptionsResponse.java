package com.ykt.platform.dto;

import java.util.List;

/**
 * 登录后的批次候选：把账号实际作业与各答案库批次做名称匹配，给出覆盖率与推荐批次。
 * percent 为「该批次中被账号作业覆盖的试卷占比」，matchedPapers/missedPapers 供前端展开查看。
 */
public record BatchOptionsResponse(
        List<AccountPaperView> papers,
        List<BatchOption> options,
        String recommended) {

    public record BatchOption(
            String batch,
            int total,
            int questions,
            int matched,
            int percent,
            List<String> matchedPapers,
            List<String> missedPapers) {
    }
}
