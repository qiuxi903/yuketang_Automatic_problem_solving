package com.ykt.platform.dto;

/** 更新兜底答题 API 配置。apiKey 为空表示保持原值不变。 */
public record AnswerApiConfigRequest(
        boolean enabled,
        String url,
        String apiKey,
        Integer timeoutMs) {
}
