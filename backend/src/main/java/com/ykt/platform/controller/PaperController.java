package com.ykt.platform.controller;

import com.ykt.platform.dto.AnswersBundleResponse;
import com.ykt.platform.dto.AnswersResponse;
import com.ykt.platform.dto.PaperManifestResponse;
import com.ykt.platform.service.AnswerLibService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/papers")
public class PaperController {

    private final AnswerLibService answerLibService;

    public PaperController(AnswerLibService answerLibService) {
        this.answerLibService = answerLibService;
    }

    @GetMapping
    public PaperManifestResponse list() {
        return answerLibService.getManifest();
    }

    @GetMapping("/{batch}/{name}/answers")
    public AnswersResponse answers(@PathVariable String batch, @PathVariable String name) {
        return answerLibService.getAnswers(batch, name);
    }

    /**
     * 答案获取：单卷（batch+paper）/ 整批（batch）/ 按日期（date）三种范围。
     * 例：/api/papers/answers?batch=10-01&amp;paper=202601、?batch=10-01、?date=2026-10-01
     */
    @GetMapping("/answers")
    public AnswersBundleResponse bundle(@RequestParam(required = false) String batch,
                                        @RequestParam(required = false) String paper,
                                        @RequestParam(required = false) String date) {
        return answerLibService.getAnswers(batch, paper, date);
    }

    /** 把选定范围的答案导出为 Markdown 附件。 */
    @GetMapping("/export")
    public ResponseEntity<byte[]> export(@RequestParam(required = false) String batch,
                                         @RequestParam(required = false) String paper,
                                         @RequestParam(required = false) String date) {
        String markdown = answerLibService.exportMarkdown(batch, paper, date);
        byte[] body = markdown.getBytes(StandardCharsets.UTF_8);
        String ascii = "answers.md";
        String encoded = URLEncoder.encode(fileName(batch, paper, date), StandardCharsets.UTF_8)
                .replace("+", "%20");
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/markdown; charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + ascii + "\"; filename*=UTF-8''" + encoded)
                .body(body);
    }

    private String fileName(String batch, String paper, String date) {
        StringBuilder sb = new StringBuilder("answers");
        if (batch != null && !batch.isBlank()) sb.append('-').append(safe(batch));
        if (paper != null && !paper.isBlank()) sb.append('-').append(safe(paper));
        if (date != null && !date.isBlank()) sb.append('-').append(safe(date));
        return sb.append(".md").toString();
    }

    private String safe(String value) {
        return value.replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
    }
}
