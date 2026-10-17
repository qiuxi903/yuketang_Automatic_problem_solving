package com.ykt.platform.dto;

import java.util.List;

public record CreateTaskRequest(String batch, String mode, Long sessionId,
                                List<String> redoPapers, String reuseYkt) {
}
