package com.ykt.platform.dto;

import java.util.List;

public record TaskItemSnapshot(
        String paper,
        String examId,
        String status,
        String score,
        String error,
        List<QuestionView> questions) {

    /** 题目完整信息：题干 + 选项（选项仅实时抓题可得）。 */
    public record QuestionView(Integer seq, String stem, List<String> options) {
    }
}
