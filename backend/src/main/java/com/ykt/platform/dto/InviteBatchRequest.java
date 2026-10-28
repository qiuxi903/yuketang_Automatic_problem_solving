package com.ykt.platform.dto;

import java.util.List;

/**
 * 邀请码批量操作请求。
 *
 * @param action 操作：disable（禁用）/ enable（启用）/ delete（删除）
 * @param codes  目标邀请码
 */
public record InviteBatchRequest(String action, List<String> codes) {
}
