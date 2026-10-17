package com.ykt.platform.dto;

import java.time.Instant;

/** 答案社区贡献流的一行：谁在什么时候以何种方式获取了哪份卷子的答案。 */
public record ContributionView(
        String username,
        String batch,
        String paper,
        int questions,
        String source,
        Long taskId,
        Instant createdAt) {
}
