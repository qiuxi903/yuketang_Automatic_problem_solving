package com.ykt.platform.dto;

import java.util.List;
import java.util.Map;

/**
 * 答案获取结果：按「单卷 / 整批 / 按日期」三种范围聚合的答案包。
 * scope 取 SINGLE | BATCH | DATE，前端据此展示获取范围与导出文件名。
 */
public record AnswersBundleResponse(
        String scope,
        String batch,
        String paper,
        String date,
        int papersCount,
        int questions,
        List<PaperEntry> papers,
        Map<String, Object> summary) {

    /** 答案包中的一套试卷：所属批次 + 试卷名 + 该卷全部题目与答案。 */
    public record PaperEntry(String batch, String paper, String date, int questions,
                             List<AnswersResponse.AnswerView> answers) {
    }
}
