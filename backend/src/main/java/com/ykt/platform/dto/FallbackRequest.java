package com.ykt.platform.dto;

import java.util.List;

/** Agent 请求兜底答题：把本地答案库未命中的题目交给配置的接口作答。 */
public record FallbackRequest(
        String batch,
        String paper,
        List<Question> questions) {

    public record Question(int seq, String stem, List<String> options) {
    }
}
