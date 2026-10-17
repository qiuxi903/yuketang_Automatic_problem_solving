package com.ykt.platform.dto;

import java.util.List;

/**
 * Agent 抓取到的题目原文（含选项），用于控制台展示「题目完整信息」。
 * 选项是答案库 md 不具备的字段，只有实时抓题才能得到。
 */
public record AgentQuestionsRequest(
        String paper,
        String examId,
        List<QuestionItem> questions) {

    public record QuestionItem(Integer seq, String stem, List<String> options) {
    }
}
