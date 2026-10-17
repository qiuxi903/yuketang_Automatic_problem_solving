package com.ykt.platform.controller;

import com.ykt.platform.dto.AnswerApiConfigRequest;
import com.ykt.platform.dto.AnswerApiConfigResponse;
import com.ykt.platform.service.AnswerApiService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 平台设置：兜底答题 API 配置。 */
@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private final AnswerApiService answerApiService;

    public SettingsController(AnswerApiService answerApiService) {
        this.answerApiService = answerApiService;
    }

    @GetMapping("/answer-api")
    public AnswerApiConfigResponse getAnswerApi() {
        return answerApiService.getConfig();
    }

    @PutMapping("/answer-api")
    public AnswerApiConfigResponse updateAnswerApi(@RequestBody AnswerApiConfigRequest request) {
        return answerApiService.updateConfig(request);
    }
}
