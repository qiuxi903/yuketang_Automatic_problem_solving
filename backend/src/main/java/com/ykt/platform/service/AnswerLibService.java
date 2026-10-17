package com.ykt.platform.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ykt.platform.config.AnswerLibProperties;
import com.ykt.platform.dto.AnswersBundleResponse;
import com.ykt.platform.dto.AnswersResponse;
import com.ykt.platform.dto.PaperManifestResponse;
import com.ykt.platform.dto.SearchHit;
import com.ykt.platform.entity.Paper;
import com.ykt.platform.entity.Question;
import com.ykt.platform.repository.PaperRepository;
import com.ykt.platform.repository.QuestionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 读取 answer-lib 下的 manifest.json 与 <batch>/<paper>.md，并把试卷题目导入数据库。
 * P1：改为采集闭环回写后由 collector 触发的增量同步，而不是启动时全量导入。
 */
@Service
public class AnswerLibService {

    private static final Logger log = LoggerFactory.getLogger(AnswerLibService.class);

    private static final Pattern STEM_PATTERN = Pattern.compile("^\\*\\*(\\d+)\\.\\*\\*\\s*(.*)$");

    private final AnswerLibProperties properties;
    private final ObjectMapper objectMapper;
    private final PaperRepository paperRepository;
    private final QuestionRepository questionRepository;

    public AnswerLibService(AnswerLibProperties properties,
                            ObjectMapper objectMapper,
                            PaperRepository paperRepository,
                            QuestionRepository questionRepository) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.paperRepository = paperRepository;
        this.questionRepository = questionRepository;
    }

    public PaperManifestResponse getManifest() {
        JsonNode root = readManifest();
        List<PaperManifestResponse.BatchView> batches = new ArrayList<>();
        int totalPapers = 0;
        int totalQuestions = 0;

        for (JsonNode batchNode : root.path("batches")) {
            List<PaperManifestResponse.PaperView> papers = new ArrayList<>();
            for (JsonNode paperNode : batchNode.path("papers")) {
                papers.add(new PaperManifestResponse.PaperView(
                        paperNode.path("name").asText(),
                        paperNode.path("file").asText(),
                        paperNode.path("questions").asInt(),
                        paperNode.path("score").asText(null)));
            }
            int papersCount = batchNode.path("papersCount").asInt(papers.size());
            int questions = batchNode.path("questions").asInt();
            batches.add(new PaperManifestResponse.BatchView(
                    batchNode.path("batch").asText(),
                    batchNode.path("date").asText(null),
                    papersCount,
                    questions,
                    papers));
            totalPapers += papersCount;
            totalQuestions += questions;
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("batches", batches.size());
        summary.put("papers", totalPapers);
        summary.put("questions", totalQuestions);
        summary.put("generatedAt", root.path("generatedAt").asText(null));
        return new PaperManifestResponse(batches, summary);
    }

    public AnswersResponse getAnswers(String batch, String paper) {
        List<AnswersResponse.AnswerView> answers = parseAnswers(mdPath(batch, paper));
        return new AnswersResponse(batch, paper, answers.size(), answers);
    }

    /**
     * 答案社区搜答案：按关键词扫描全部批次的题目（题干或答案包含即命中，不分大小写）。
     * 每卷最多命中的题目全量返回，总命中封顶 limit，避免大关键词慢查询。
     */
    public List<SearchHit> search(String query, int limit) {
        List<SearchHit> hits = new ArrayList<>();
        String keyword = query == null ? "" : query.strip().toLowerCase();
        if (keyword.isEmpty()) {
            return hits;
        }
        outer:
        for (PaperManifestResponse.BatchView batchView : getManifest().batches()) {
            for (PaperManifestResponse.PaperView paperView : batchView.papers()) {
                List<AnswersResponse.AnswerView> answers = parseAnswers(
                        mdPath(batchView.batch(), paperView.name()));
                for (AnswersResponse.AnswerView answer : answers) {
                    boolean stemHit = answer.stem() != null && answer.stem().toLowerCase().contains(keyword);
                    boolean answerHit = answer.answer() != null && answer.answer().toLowerCase().contains(keyword);
                    if (!stemHit && !answerHit) {
                        continue;
                    }
                    hits.add(new SearchHit(batchView.batch(), paperView.name(), batchView.date(),
                            answer.seq(), answer.stem(), answer.answer()));
                    if (hits.size() >= limit) {
                        break outer;
                    }
                }
            }
        }
        return hits;
    }

    public List<String> paperNames(String batch) {
        List<String> names = new ArrayList<>();
        for (JsonNode batchNode : readManifest().path("batches")) {
            if (batch.equals(batchNode.path("batch").asText())) {
                for (JsonNode paperNode : batchNode.path("papers")) {
                    names.add(paperNode.path("name").asText());
                }
            }
        }
        if (names.isEmpty()) {
            // manifest 未登记时按目录兜底，避免手工落盘的批次读不到
            Path dir = Paths.get(properties.dir()).resolve(batch);
            if (Files.isDirectory(dir)) {
                try (var stream = Files.list(dir)) {
                    stream.filter(path -> path.getFileName().toString().endsWith(".md"))
                            .map(path -> path.getFileName().toString().replaceFirst("\\.md$", ""))
                            .sorted()
                            .forEach(names::add);
                } catch (IOException ex) {
                    log.warn("列出批次目录失败 {}：{}", dir, ex.getMessage());
                }
            }
        }
        return names;
    }

    /**
     * 按范围读取答案：单卷（batch+paper）/ 整批（batch）/ 按日期（date）。
     * 三者优先级：paper &gt; batch &gt; date。
     */
    public AnswersBundleResponse getAnswers(String batch, String paper, String date) {
        boolean hasBatch = batch != null && !batch.isBlank();
        boolean hasPaper = paper != null && !paper.isBlank();
        boolean hasDate = date != null && !date.isBlank();
        if (!hasBatch && !hasDate) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请指定 batch 或 date");
        }
        if (hasPaper && !hasBatch) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "单卷获取需要同时指定 batch 与 paper");
        }

        String scope = hasPaper ? "SINGLE" : hasBatch ? "BATCH" : "DATE";
        List<AnswersBundleResponse.PaperEntry> entries = new ArrayList<>();
        if ("SINGLE".equals(scope)) {
            entries.add(entry(batch, paper));
        } else if ("BATCH".equals(scope)) {
            for (String name : paperNames(batch)) {
                entries.add(entry(batch, name));
            }
        } else {
            for (JsonNode batchNode : readManifest().path("batches")) {
                if (!date.equals(batchNode.path("date").asText())) continue;
                String owner = batchNode.path("batch").asText();
                for (JsonNode paperNode : batchNode.path("papers")) {
                    entries.add(entry(owner, paperNode.path("name").asText()));
                }
            }
        }

        int questions = entries.stream().mapToInt(AnswersBundleResponse.PaperEntry::questions).sum();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("scope", scope);
        summary.put("papers", entries.size());
        summary.put("questions", questions);
        summary.put("batch", hasBatch ? batch : null);
        summary.put("paper", hasPaper ? paper : null);
        summary.put("date", hasDate ? date : null);
        return new AnswersBundleResponse(scope, hasBatch ? batch : null, hasPaper ? paper : null,
                hasDate ? date : null, entries.size(), questions, entries, summary);
    }

    /** 按同一范围导出 Markdown：标题标注范围，正文逐卷列出题号、题干与答案。 */
    public String exportMarkdown(String batch, String paper, String date) {
        AnswersBundleResponse bundle = getAnswers(batch, paper, date);
        StringBuilder sb = new StringBuilder();
        sb.append("# 答案导出 · ").append(scopeLabel(bundle.scope())).append("\n\n");
        if (bundle.batch() != null) sb.append("- 批次：").append(bundle.batch()).append('\n');
        if (bundle.paper() != null) sb.append("- 试卷：").append(bundle.paper()).append('\n');
        if (bundle.date() != null) sb.append("- 日期：").append(bundle.date()).append('\n');
        sb.append("- 试卷数：").append(bundle.papersCount()).append('\n');
        sb.append("- 题目数：").append(bundle.questions()).append('\n');
        sb.append("- 导出时间：")
                .append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
                .append("\n\n");
        for (AnswersBundleResponse.PaperEntry entry : bundle.papers()) {
            sb.append("## ").append(entry.paper());
            if (entry.batch() != null) sb.append("（").append(entry.batch()).append("）");
            sb.append("\n\n");
            for (AnswersResponse.AnswerView answer : entry.answers()) {
                sb.append("**").append(answer.seq()).append(".** ").append(answer.stem()).append('\n');
                sb.append("- 答案：").append(answer.answer()).append("\n\n");
            }
        }
        return sb.toString();
    }

    private String scopeLabel(String scope) {
        return switch (scope) {
            case "SINGLE" -> "单个试卷";
            case "BATCH" -> "整批获取";
            default -> "按日期获取";
        };
    }

    private AnswersBundleResponse.PaperEntry entry(String batch, String paper) {
        List<AnswersResponse.AnswerView> answers = parseAnswers(mdPath(batch, paper));
        return new AnswersBundleResponse.PaperEntry(batch, paper, batchDate(batch), answers.size(), answers);
    }

    private String batchDate(String batch) {
        for (JsonNode batchNode : readManifest().path("batches")) {
            if (batch.equals(batchNode.path("batch").asText())) {
                return batchNode.path("date").asText(null);
            }
        }
        return null;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void importOnStartup() {
        try {
            int imported = importToDatabase();
            log.info("答案库导入完成，试卷 {} 套", imported);
        } catch (RuntimeException ex) {
            // 导入失败不应阻断服务启动，接口仍可直接读文件返回
            log.warn("答案库导入失败：{}", ex.getMessage());
        }
    }

    @Transactional
    public int importToDatabase() {
        int count = 0;
        for (JsonNode batchNode : readManifest().path("batches")) {
            String batch = batchNode.path("batch").asText();
            for (JsonNode paperNode : batchNode.path("papers")) {
                String name = paperNode.path("name").asText();
                int questions = paperNode.path("questions").asInt();
                Paper paper = paperRepository.findByBatchAndName(batch, name).orElseGet(() -> {
                    Paper created = new Paper();
                    created.setBatch(batch);
                    created.setName(name);
                    created.setSource("import");
                    return created;
                });
                paper.setTotal(questions);
                Paper saved = paperRepository.save(paper);
                if (questionRepository.countByPaperId(saved.getId()) == 0) {
                    saveQuestions(saved.getId(), parseAnswers(mdPath(batch, name)));
                }
                count++;
            }
        }
        return count;
    }

    private void saveQuestions(Long paperId, List<AnswersResponse.AnswerView> answers) {
        List<Question> questions = new ArrayList<>();
        for (AnswersResponse.AnswerView view : answers) {
            Question question = new Question();
            question.setPaperId(paperId);
            question.setSeq(view.seq());
            question.setStem(view.stem());
            question.setAnswerText(view.answer());
            question.setCollected(true);
            questions.add(question);
        }
        questionRepository.saveAll(questions);
    }

    List<AnswersResponse.AnswerView> parseAnswers(Path path) {
        List<AnswersResponse.AnswerView> answers = new ArrayList<>();
        if (!Files.isRegularFile(path)) {
            return answers;
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            log.warn("读取答案文件失败 {}：{}", path, ex.getMessage());
            return answers;
        }

        Integer seq = null;
        StringBuilder stem = new StringBuilder();
        for (String raw : lines) {
            String line = raw.strip();
            Matcher matcher = STEM_PATTERN.matcher(line);
            if (matcher.matches()) {
                seq = Integer.valueOf(matcher.group(1));
                stem.setLength(0);
                stem.append(matcher.group(2).strip());
                continue;
            }
            if (seq != null && line.startsWith("- 答案")) {
                answers.add(new AnswersResponse.AnswerView(seq, stem.toString(), extractAnswer(line)));
                seq = null;
                continue;
            }
            if (seq != null && !line.isEmpty()) {
                // 题干可能跨行
                stem.append(' ').append(line);
            }
        }
        return answers;
    }

    private String extractAnswer(String line) {
        int idx = line.indexOf('：');
        if (idx < 0) {
            idx = line.indexOf(':');
        }
        return idx < 0 ? "" : line.substring(idx + 1).strip();
    }

    private Path mdPath(String batch, String paper) {
        return Paths.get(properties.dir()).resolve(batch).resolve(paper + ".md");
    }

    private JsonNode readManifest() {
        Path manifest = Paths.get(properties.dir()).resolve("manifest.json");
        try {
            return objectMapper.readTree(Files.readString(manifest, StandardCharsets.UTF_8));
        } catch (IOException ex) {
            log.warn("读取 manifest 失败 {}：{}", manifest, ex.getMessage());
            return objectMapper.createObjectNode();
        }
    }
}
