package com.ykt.platform.dto;

/** 管理端发放权益请求（三选一字段，按 kind 使用）。 */
public record VipGrantRequest(String kind, Integer days, Integer quota, String targetBatch) {
}
