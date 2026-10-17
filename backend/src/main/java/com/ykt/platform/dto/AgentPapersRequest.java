package com.ykt.platform.dto;

import java.util.List;

/** Agent 回传的账号实际作业清单（含所在课程名，便于控制台展示账号上下文）。 */
public record AgentPapersRequest(List<AccountPaperView> papers, String course) {
}
