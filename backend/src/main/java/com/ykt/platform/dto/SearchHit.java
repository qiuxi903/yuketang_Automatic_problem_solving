package com.ykt.platform.dto;

/** 答案社区搜答案命中的一条：题干 + 正确答案 + 出处（批次/试卷）。 */
public record SearchHit(
        String batch,
        String paper,
        String date,
        int seq,
        String stem,
        String answer) {
}
