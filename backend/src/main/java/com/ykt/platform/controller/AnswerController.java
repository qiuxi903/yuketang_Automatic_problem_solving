package com.ykt.platform.controller;

import com.ykt.platform.dto.FallbackRequest;
import com.ykt.platform.dto.FallbackResponse;
import com.ykt.platform.service.AnswerApiService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 兜底答题内部接口，供 Agent 调用。P0 不校验内部令牌，与 /api/agent/** 同级，P1 增加。
 */
@RestController
@RequestMapping("/api/answer")
public class AnswerController {

    private final AnswerApiService answerApiService;

    public AnswerController(AnswerApiService answerApiService) {
        this.answerApiService = answerApiService;
    }

    @PostMapping("/fallback")
    public FallbackResponse fallback(@RequestBody FallbackRequest request) {
        return answerApiService.fallback(request);
    }
}
