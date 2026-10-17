package com.ykt.platform.dto;

import java.util.List;

public record AnswersResponse(String batch, String paper, int questions, List<AnswerView> answers) {

    public record AnswerView(int seq, String stem, String answer) {
    }
}
