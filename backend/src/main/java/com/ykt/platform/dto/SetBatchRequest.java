package com.ykt.platform.dto;

import java.util.List;

public record SetBatchRequest(String batch, String mode, List<String> redoPapers) {
}
