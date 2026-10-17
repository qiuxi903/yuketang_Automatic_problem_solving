package com.ykt.platform.dto;

/** 兜底答题 API 配置出参，不回声 apiKey，仅告知是否已配置。 */
public record AnswerApiConfigResponse(
        boolean enabled,
        String url,
        boolean apiKeySet,
        int timeoutMs) {
}
