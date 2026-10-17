package com.ykt.platform.dto;

import java.util.List;

/** 兜底答题出参。answer 为答案文本（选项内容或字母），由 Agent 再映射为选项索引。 */
public record FallbackResponse(List<Answer> answers) {

    public record Answer(int seq, String answer) {
    }
}
