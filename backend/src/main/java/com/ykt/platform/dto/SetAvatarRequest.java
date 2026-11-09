package com.ykt.platform.dto;

/** 设置头像（base64 dataURL，前端压缩到 ≤200KB）。 */
public record SetAvatarRequest(String avatar) {
}
