package com.ykt.platform.dto;

public record AgentProgressRequest(
        String paper,
        String examId,
        Integer seq,
        Integer total,
        String score,
        String status,
        String message) {
}
