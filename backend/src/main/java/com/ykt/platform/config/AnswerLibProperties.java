package com.ykt.platform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ykt.answer-lib")
public record AnswerLibProperties(String dir) {
}
