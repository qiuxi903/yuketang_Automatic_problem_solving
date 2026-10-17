package com.ykt.platform.dto;

import java.util.List;
import java.util.Map;

public record PaperManifestResponse(List<BatchView> batches, Map<String, Object> summary) {

    public record BatchView(String batch, String date, int papersCount, int questions, List<PaperView> papers) {
    }

    public record PaperView(String name, String file, int questions, String score) {
    }
}
