package com.ykt.platform.dto;

/** Agent 扫码登录后从学习日志抓到的单条账号作业。 */
public record AccountPaperView(
        String name,
        Integer questionCount,
        Integer fullScore,
        String score,
        String status, String date) {
}
