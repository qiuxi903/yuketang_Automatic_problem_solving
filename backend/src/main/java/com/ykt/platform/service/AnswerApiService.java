package com.ykt.platform.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ykt.platform.dto.AnswerApiConfigRequest;
import com.ykt.platform.dto.AnswerApiConfigResponse;
import com.ykt.platform.dto.FallbackRequest;
import com.ykt.platform.dto.FallbackResponse;
import com.ykt.platform.entity.AnswerApiConfig;
import com.ykt.platform.repository.AnswerApiConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 兜底答题 API 的配置与转发。
 *
 * 转发约定（自建/第三方接口需实现）：
 *   请求 POST {url}，JSON 体 {batch, paper, questions:[{seq, stem, options[]}]}
 *   响应 JSON {answers:[{seq, answer}]}，也兼容 answers:["A","B",...] 的纯数组形式
 */
@Service
public class AnswerApiService {

    private static final Logger log = LoggerFactory.getLogger(AnswerApiService.class);

    private final AnswerApiConfigRepository repository;
    private final ObjectMapper objectMapper;

    public AnswerApiService(AnswerApiConfigRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public AnswerApiConfigResponse getConfig() {
        return toResponse(load());
    }

    @Transactional
    public AnswerApiConfigResponse updateConfig(AnswerApiConfigRequest request) {
        AnswerApiConfig config = load();
        config.setEnabled(request.enabled());
        config.setUrl(trimToNull(request.url()));
        if (request.timeoutMs() != null && request.timeoutMs() > 0) {
            config.setTimeoutMs(request.timeoutMs());
        }
        // apiKey 留空表示不修改，避免前端无法回显密钥时被清空
        if (request.apiKey() != null && !request.apiKey().isBlank()) {
            config.setApiKey(request.apiKey().trim());
        }
        return toResponse(repository.save(config));
    }

    /** 把未命中的题目转发给配置的接口作答；未启用或未配置时返回空列表。 */
    public FallbackResponse fallback(FallbackRequest request) {
        AnswerApiConfig config = load();
        if (!config.isEnabled() || config.getUrl() == null || config.getUrl().isBlank()) {
            log.info("兜底答题未启用或未配置，跳过 {} 道题", request.questions() == null ? 0 : request.questions().size());
            return new FallbackResponse(List.of());
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("batch", request.batch());
        body.put("paper", request.paper());
        body.put("questions", request.questions());

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(config.getTimeoutMs());
        factory.setReadTimeout(config.getTimeoutMs());

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            if (config.getApiKey() != null && !config.getApiKey().isBlank()) {
                headers.setBearerAuth(config.getApiKey());
            }
            // 显式序列化 JSON 字符串，避免依赖客户端的默认消息转换器
            HttpEntity<String> entity = new HttpEntity<>(objectMapper.writeValueAsString(body), headers);
            ResponseEntity<String> response = new RestTemplate(factory)
                    .postForEntity(config.getUrl(), entity, String.class);
            return parse(response.getBody());
        } catch (Exception ex) {
            log.warn("兜底答题接口调用失败：{}", ex.getMessage());
            return new FallbackResponse(List.of());
        }
    }

    private FallbackResponse parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return new FallbackResponse(List.of());
        }
        try {
            JsonNode root = objectMapper.readTree(raw);
            JsonNode answers = root.path("answers");
            List<FallbackResponse.Answer> out = new ArrayList<>();
            if (answers.isArray()) {
                for (int i = 0; i < answers.size(); i++) {
                    JsonNode node = answers.get(i);
                    if (node.isObject()) {
                        int seq = node.path("seq").asInt(i + 1);
                        out.add(new FallbackResponse.Answer(seq, node.path("answer").asText("")));
                    } else if (node.isTextual()) {
                        out.add(new FallbackResponse.Answer(i + 1, node.asText()));
                    }
                }
            }
            return new FallbackResponse(out);
        } catch (Exception ex) {
            log.warn("兜底答题响应解析失败：{}", ex.getMessage());
            return new FallbackResponse(List.of());
        }
    }

    private AnswerApiConfig load() {
        return repository.findById(AnswerApiConfig.SINGLETON_ID).orElseGet(() -> {
            AnswerApiConfig created = new AnswerApiConfig();
            created.setId(AnswerApiConfig.SINGLETON_ID);
            return created;
        });
    }

    private AnswerApiConfigResponse toResponse(AnswerApiConfig config) {
        return new AnswerApiConfigResponse(config.isEnabled(), config.getUrl(),
                config.getApiKey() != null && !config.getApiKey().isBlank(), config.getTimeoutMs());
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
