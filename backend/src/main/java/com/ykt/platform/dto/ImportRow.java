package com.ykt.platform.dto;

/** 批量导入账号的单行：Excel 解析出的用户名/密码（明文，入库前加密）。 */
public record ImportRow(String username, String password) {
}
