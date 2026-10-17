package com.ykt.platform.dto;

import java.util.List;

/** Agent 回传的正确答案。source：collect=结果页新采集（计入社区贡献），reuse=复用库内答案（不计贡献）。 */
public record AgentAnswersRequest(
        String paper,
        String examId,
        String source,
        List<AnswerItem> items) {

    public record AnswerItem(Integer seq, String stem, String answer) {
    }
}
