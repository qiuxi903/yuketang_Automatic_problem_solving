package com.ykt.platform.dto;

/** 批量导入的单行结果：ok=false 时 reason 说明原因（用户名重复/格式不符等）。 */
public record ImportResult(String username, boolean ok, String reason) {
}
