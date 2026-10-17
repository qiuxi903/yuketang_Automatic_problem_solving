package com.ykt.platform.dto;

/** 控制台拉取的最近一帧 Agent 实时截图。 */
public record TaskFrameResponse(String image, String url, Long updatedAt) {
}
