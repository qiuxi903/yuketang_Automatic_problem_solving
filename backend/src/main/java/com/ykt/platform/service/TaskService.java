package com.ykt.platform.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import com.ykt.platform.dto.AccountPaperView;
import com.ykt.platform.dto.AgentAnswersRequest;
import com.ykt.platform.dto.AgentFinishRequest;
import com.ykt.platform.dto.AgentFrameRequest;
import com.ykt.platform.dto.AgentPapersRequest;
import com.ykt.platform.dto.AgentProgressRequest;
import com.ykt.platform.dto.AgentQuestionsRequest;
import com.ykt.platform.dto.BatchDecisionResponse;
import com.ykt.platform.dto.BatchOptionsResponse;
import com.ykt.platform.dto.CreateTaskRequest;
import com.ykt.platform.dto.OkResponse;
import com.ykt.platform.dto.PaperManifestResponse;
import com.ykt.platform.dto.TaskCreateResponse;
import com.ykt.platform.dto.TaskFrameResponse;
import com.ykt.platform.dto.TaskItemSnapshot;
import com.ykt.platform.dto.TaskSnapshot;
import com.ykt.platform.entity.Task;
import com.ykt.platform.entity.TaskItem;
import com.ykt.platform.entity.TaskItemStatus;
import com.ykt.platform.entity.TaskStatus;
import com.ykt.platform.repository.PaperRepository;
import com.ykt.platform.repository.TaskItemRepository;
import com.ykt.platform.repository.TaskRepository;
import com.ykt.platform.ws.TaskWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class TaskService {

    private static final Logger log = LoggerFactory.getLogger(TaskService.class);

    private final TaskRepository taskRepository;
    private final TaskItemRepository taskItemRepository;
    private final PaperRepository paperRepository;
    private final AnswerLibService answerLibService;
    private final SessionService sessionService;
    private final TaskQueue taskQueue;
    private final TaskWebSocketHandler webSocketHandler;
    private final ObjectMapper objectMapper;
    private final CommunityService communityService;

    /** 浏览器模式：任务 → 最近一帧实时截图（内存缓存，重启即失效，无需持久化）。 */
    private final Map<Long, TaskFrameResponse> frameCache = new ConcurrentHashMap<>();

    public TaskService(TaskRepository taskRepository,
                       TaskItemRepository taskItemRepository,
                       PaperRepository paperRepository,
                       AnswerLibService answerLibService,
                       SessionService sessionService,
                       TaskQueue taskQueue,
                       TaskWebSocketHandler webSocketHandler,
                       ObjectMapper objectMapper,
                       CommunityService communityService) {
        this.taskRepository = taskRepository;
        this.taskItemRepository = taskItemRepository;
        this.paperRepository = paperRepository;
        this.answerLibService = answerLibService;
        this.sessionService = sessionService;
        this.taskQueue = taskQueue;
        this.webSocketHandler = webSocketHandler;
        this.objectMapper = objectMapper;
        this.communityService = communityService;
    }

    /**
     * 数据库已持久化，后端重启后「执行中 / 待确认批次」任务对应的 Agent 调度已随进程丢失，
     * 若不处理会永远停在原状态误导用户。启动时统一标记为失败，提示重新发起；
     * PENDING 保留（Agent 会按既有回落逻辑领取），PAUSED 保留（用户恢复后重新排队执行）。
     */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void failOrphanedInFlightTasks() {
        List<Task> orphans = taskRepository
                .findByStatusIn(List.of(TaskStatus.RUNNING, TaskStatus.WAITING_BATCH));
        if (orphans.isEmpty()) {
            return;
        }
        for (Task task : orphans) {
            task.setStatus(TaskStatus.FAILED);
            task.setMessage("后端已重启，任务被中断，请重新发起");
        }
        taskRepository.saveAll(orphans);
        log.info("已把 {} 个因重启而中断的任务标记为失败", orphans.size());
    }

    /** 进行中的任务状态集：同一平台账号同时只允许存在一个。 */
    private static final List<TaskStatus> ACTIVE_STATUSES =
            List.of(TaskStatus.PENDING, TaskStatus.RUNNING, TaskStatus.WAITING_BATCH, TaskStatus.PAUSED);

    @Transactional
    public TaskCreateResponse create(Long userId, CreateTaskRequest request) {
        // 平台账号一次只能跑一个任务：已有进行中任务时拒绝新建（避免并行互踢/进度混乱）
        List<Task> active = taskRepository.findByUserIdAndStatusIn(userId, ACTIVE_STATUSES);
        if (!active.isEmpty()) {
            Task cur = active.get(0);
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "您已有进行中的任务 #" + cur.getId() + "（" + cur.getStatus().name() + "），一个账号同时只能执行一个任务；请先完成、暂停或中断它");
        }
        if (request.sessionId() != null) {
            sessionService.findOwned(userId, request.sessionId());
        }
        // 批次可后置：为空时先扫码登录，待 Agent 回传账号作业清单后再由用户确认批次
        String batch = request.batch() == null || request.batch().isBlank() ? null : request.batch();
        List<String> paperNames = batch == null ? List.of() : answerLibService.paperNames(batch);
        // 重做任务：只做指定卷子（通常来自上一任务「取答案未做」的弹窗一键重做）
        List<String> redo = request.redoPapers() == null ? List.of() : request.redoPapers().stream()
                .filter(name -> name != null && !name.isBlank()).toList();

        Task task = new Task();
        task.setUserId(userId);
        task.setSessionId(request.sessionId());
        task.setBatch(batch);
        task.setReuseYkt(request.reuseYkt() == null || request.reuseYkt().isBlank()
                ? null : request.reuseYkt());
        task.setMode(request.mode() == null || request.mode().isBlank() ? "answer" : request.mode());
        task.setStatus(TaskStatus.PENDING);
        try {
            task.setRedoJson(redo.isEmpty() ? null : objectMapper.writeValueAsString(redo));
        } catch (JsonProcessingException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "重做清单序列化失败: " + ex.getMessage());
        }

        List<String> itemNames;
        if (!redo.isEmpty()) {
            // 重做任务的明细与总数只按「重做清单 ∩ 批次」算
            itemNames = paperNames.stream()
                    .filter(name -> redo.stream().anyMatch(r -> r.equals(name) || r.contains(name) || name.contains(r)))
                    .toList();
            if (itemNames.isEmpty()) {
                itemNames = redo;
            }
            task.setTotalPaper(itemNames.size());
            task.setMessage(String.format("重做任务：将重做 %d 套（答案已采集，作答以最新答案库为准）", itemNames.size()));
        } else {
            itemNames = paperNames;
            task.setTotalPaper(paperNames.size());
            task.setMessage(batch == null
                    ? "任务已创建，扫码登录后按账号实际作业选择批次"
                    : "任务已创建，等待 Agent 领取");
        }
        Task saved = taskRepository.save(task);

        if (!itemNames.isEmpty()) {
            List<TaskItem> items = new ArrayList<>();
            for (String paperName : itemNames) {
                TaskItem item = new TaskItem();
                item.setTaskId(saved.getId());
                item.setPaperName(paperName);
                item.setStatus(TaskItemStatus.PENDING);
                paperRepository.findByBatchAndName(batch, paperName)
                        .ifPresent(paper -> item.setPaperId(paper.getId()));
                items.add(item);
            }
            taskItemRepository.saveAll(items);
        }

        taskQueue.enqueue(saved.getId());
        broadcast(saved.getId());
        return new TaskCreateResponse(saved.getId(), saved.getStatus().name(), saved.getTotalPaper());
    }

    public List<TaskSnapshot> list(Long userId) {
        return taskRepository.findByUserIdOrderByIdDesc(userId).stream()
                .map(this::toSnapshot)
                .toList();
    }

    public TaskSnapshot get(Long userId, Long id) {
        return toSnapshot(requireOwned(userId, id));
    }

    public TaskSnapshot snapshot(Long taskId) {
        return toSnapshot(require(taskId));
    }

    @Transactional
    public OkResponse pause(Long userId, Long id) {
        Task task = requireOwned(userId, id);
        if (task.getStatus() == TaskStatus.RUNNING || task.getStatus() == TaskStatus.PENDING) {
            task.setStatus(TaskStatus.PAUSED);
            task.setMessage("任务已暂停，Agent 将在安全点停下；点「继续」可接着做");
            taskRepository.save(task);
            // P1：通过 Redis 控制通道通知正在执行的 Agent 暂停当前试卷
            broadcast(task.getId());
        }
        return OkResponse.of();
    }

    @Transactional
    public OkResponse resume(Long userId, Long id) {
        Task task = requireOwned(userId, id);
        if (task.getStatus() == TaskStatus.PAUSED) {
            task.setStatus(TaskStatus.PENDING);
            task.setMessage("任务已恢复，等待 Agent 继续");
            taskRepository.save(task);
            taskQueue.enqueue(task.getId());
            broadcast(task.getId());
        }
        return OkResponse.of();
    }

    /**
     * 中断任务：仅未结束的任务可中断，中断后置为终端态 {@link TaskStatus#CANCELLED}。
     * 中断同时把任务移出调度队列，避免 Agent 再次领取；Agent 后续回传一律被忽略。
     */
    @Transactional
    public OkResponse interrupt(Long userId, Long id) {
        Task task = requireOwned(userId, id);
        if (!isInterruptible(task.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "当前任务状态不允许中断");
        }
        taskQueue.remove(id);
        task.setStatus(TaskStatus.CANCELLED);
        task.setMessage("任务已被用户中断");
        taskRepository.save(task);
        broadcast(id);
        return OkResponse.of();
    }

    /**
     * 删除任务：连带删除任务明细，并清理内存中的调度队列与截图缓存。
     * 任务删除后其会话（若有）保持不变，会话自身有独立的过期与关闭机制。
     */
    @Transactional
    public OkResponse delete(Long userId, Long id) {
        Task task = requireOwned(userId, id);
        taskQueue.remove(id);
        frameCache.remove(id);
        taskItemRepository.deleteAll(taskItemRepository.findByTaskIdOrderByIdAsc(id));
        taskRepository.delete(task);
        // 任务已不存在，无法再广播快照；前端按删除结果在本地任务列表剔除
        return OkResponse.of();
    }

    private boolean isInterruptible(TaskStatus status) {
        return status == TaskStatus.PENDING
                || status == TaskStatus.RUNNING
                || status == TaskStatus.WAITING_BATCH
                || status == TaskStatus.PAUSED;
    }

    /** 运行中的任务快照：Agent 启动时收养「上次进程崩溃时正在执行」的孤儿任务。 */
    public List<TaskSnapshot> runningTasks() {
        return taskRepository.findByStatus(TaskStatus.RUNNING).stream()
                .map(this::toSnapshot)
                .toList();
    }

    /** Agent 轮询领取任务：优先取内存队列，队列为空时回落数据库，避免后端重启后 PENDING 任务无人领取。 */
    @Transactional
    public TaskSnapshot agentNext() {
        Long taskId;
        while ((taskId = taskQueue.poll()) != null) {
            Task task = taskRepository.findById(taskId).orElse(null);
            if (task == null || task.getStatus() != TaskStatus.PENDING) {
                continue;
            }
            return claim(task);
        }
        // 内存队列不是事实来源：重启后队列清空，靠数据库把遗留的 PENDING 任务捞回来
        return taskRepository.findFirstByStatusOrderByIdAsc(TaskStatus.PENDING)
                .map(this::claim)
                .orElse(null);
    }

    private TaskSnapshot claim(Task task) {
        taskQueue.remove(task.getId());
        task.setStatus(TaskStatus.RUNNING);
        task.setMessage("Agent 已领取，准备打开雨课堂");
        taskRepository.save(task);
        broadcast(task.getId());
        return toSnapshot(task);
    }

    /** Agent 扫码登录后回传账号实际作业清单，任务转入「待确认批次」。 */
    @Transactional
    public OkResponse recordPapers(Long taskId, AgentPapersRequest request) {
        Task task = require(taskId);
        if (task.getStatus() == TaskStatus.CANCELLED) {
            // 任务已被中断：忽略 Agent 迟到的作业清单回传
            return OkResponse.of();
        }
        List<AccountPaperView> papers = request.papers() == null ? List.of() : request.papers();
        try {
            task.setPapersJson(objectMapper.writeValueAsString(papers));
        } catch (JsonProcessingException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "作业清单序列化失败: " + ex.getMessage());
        }
        if (request.course() != null && !request.course().isBlank()) {
            task.setCourseName(request.course());
        }
        task.setStatus(TaskStatus.WAITING_BATCH);
        task.setMessage(papers.isEmpty()
                ? "已登录，但未在课程下抓到试卷，请检查课程配置"
                : "已登录。请在试卷清单勾选要处理的卷子，然后点「做题」或「取答案」");
        taskRepository.save(task);
        broadcast(taskId);
        return OkResponse.of();
    }

    /** Agent 轮询批次决策：返回当前任务状态、已确认的批次与作答模式。 */
    public BatchDecisionResponse batchDecision(Long taskId) {
        Task task = require(taskId);
        return new BatchDecisionResponse(task.getStatus().name(), task.getBatch(), task.getMode());
    }

    /** 登录后按账号作业清单给出各答案库批次的覆盖率与推荐批次。 */
    public BatchOptionsResponse batchOptions(Long userId, Long taskId) {
        Task task = requireOwned(userId, taskId);
        return buildBatchOptions(task.getPapersJson());
    }

    /** 用户确认批次与作答模式：补齐任务明细并把任务交回 Agent 继续作答。 */
    @Transactional
    public OkResponse setBatch(Long userId, Long taskId, String batch, String mode, List<String> redoPapers) {
        if (batch == null || batch.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "batch 不能为空");
        }
        Task task = requireOwned(userId, taskId);
        if (task.getStatus() != TaskStatus.WAITING_BATCH) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "当前任务状态不允许选择批次");
        }
        List<String> paperNames = answerLibService.paperNames(batch);
        if (paperNames.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "批次不存在: " + batch);
        }
        // 总数与明细只按「批次 ∩ 账号实际作业」算：Agent 只处理账号里真实存在的卷子，
        // 若按整批（可能 30 套）算，进度会被稀释成永远的小百分比（用户看到 0%/20% 以为卡死）
        List<AccountPaperView> account = readPapers(task.getPapersJson());
        // 勾选清单优先：点了「做题/取答案」并带勾选时，任务只做勾选的卷子
        // （勾选的可能不在答案库批次里——新卷子也能做，做完采答案入库）
        List<String> redo = redoPapers == null ? List.of() : redoPapers.stream()
                .filter(name -> name != null && !name.isBlank())
                .toList();
        List<String> plan;
        if (!redo.isEmpty()) {
            List<String> matched = paperNames.stream()
                    .filter(name -> redo.stream().anyMatch(r -> r.equals(name) || r.contains(name) || name.contains(r)))
                    .toList();
            plan = matched.isEmpty() ? redo : matched;
        } else {
            plan = paperNames;
            if (!account.isEmpty()) {
                List<String> matched = paperNames.stream()
                        .filter(name -> account.stream().anyMatch(p -> p.name() != null
                                && (p.name().contains(name) || name.contains(p.name()))))
                        .toList();
                if (!matched.isEmpty()) {
                    plan = matched;
                }
            }
        }

        // 模式后置：登录后确认，缺省沿用任务创建时的模式
        String resolvedMode = (mode == null || mode.isBlank()) ? task.getMode() : mode;
        task.setMode(resolvedMode);
        task.setBatch(batch);
        try {
            task.setRedoJson(redoPapers == null || redoPapers.isEmpty()
                    ? null : objectMapper.writeValueAsString(redoPapers));
        } catch (JsonProcessingException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "重做清单序列化失败: " + ex.getMessage());
        }
        task.setTotalPaper(plan.size());
        task.setStatus(TaskStatus.RUNNING);
        task.setMessage("批次与模式已确认，Agent 开始作答");
        taskRepository.save(task);

        if (taskItemRepository.findByTaskIdOrderByIdAsc(taskId).isEmpty()) {
            List<TaskItem> items = new ArrayList<>();
            for (String paperName : plan) {
                TaskItem item = new TaskItem();
                item.setTaskId(taskId);
                item.setPaperName(paperName);
                item.setStatus(TaskItemStatus.PENDING);
                paperRepository.findByBatchAndName(batch, paperName)
                        .ifPresent(paper -> item.setPaperId(paper.getId()));
                items.add(item);
            }
            taskItemRepository.saveAll(items);
        }

        if (task.getSessionId() != null) {
            sessionService.updateBatch(task.getSessionId(), batch);
            sessionService.updateMode(task.getSessionId(), resolvedMode);
        }
        broadcast(taskId);
        return OkResponse.of();
    }

    private BatchOptionsResponse buildBatchOptions(String papersJson) {
        List<AccountPaperView> papers = readPapers(papersJson);
        List<String> names = papers.stream()
                .map(AccountPaperView::name)
                .filter(name -> name != null && !name.isBlank())
                .toList();

        List<BatchOptionsResponse.BatchOption> options = new ArrayList<>();
        for (PaperManifestResponse.BatchView batch : answerLibService.getManifest().batches()) {
            List<String> matched = new ArrayList<>();
            List<String> missed = new ArrayList<>();
            for (PaperManifestResponse.PaperView paper : batch.papers()) {
                // 与 Agent 的匹配规则一致：账号作业名包含答案库试卷名即视为命中
                boolean covered = names.stream().anyMatch(name -> name.contains(paper.name()));
                (covered ? matched : missed).add(paper.name());
            }
            int total = batch.papers().size();
            int percent = total == 0 ? 0 : (int) Math.round(matched.size() * 100.0 / total);
            options.add(new BatchOptionsResponse.BatchOption(
                    batch.batch(), total, batch.questions(), matched.size(), percent, matched, missed));
        }
        options.sort(Comparator.comparingInt(BatchOptionsResponse.BatchOption::matched).reversed()
                .thenComparing(Comparator.comparingInt(BatchOptionsResponse.BatchOption::percent).reversed()));
        String recommended = options.stream()
                .filter(option -> option.matched() > 0)
                .findFirst()
                .map(BatchOptionsResponse.BatchOption::batch)
                .orElse(null);
        return new BatchOptionsResponse(papers, options, recommended);
    }

    private List<AccountPaperView> readPapers(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (Exception ex) {
            log.warn("解析账号作业清单失败：{}", ex.getMessage());
            return List.of();
        }
    }

    @Transactional
    public OkResponse updateQrcode(Long taskId, String qrcode) {
        Task task = require(taskId);
        if (task.getSessionId() != null) {
            sessionService.updateQrcode(task.getSessionId(), qrcode);
        }
        return OkResponse.of();
    }

    @Transactional
    public OkResponse updateSessionStatus(Long taskId, String status, String yktName) {
        Task task = require(taskId);
        if (task.getSessionId() != null) {
            sessionService.updateStatus(task.getSessionId(), status, yktName);
        }
        return OkResponse.of();
    }

    @Transactional
    public OkResponse updateProgress(Long taskId, AgentProgressRequest request) {
        Task task = require(taskId);
        if (task.getStatus() == TaskStatus.CANCELLED) {
            // 任务已被中断：忽略 Agent 迟到的进度回传，防止状态被改回
            return OkResponse.of();
        }
        if (request.paper() != null) {
            task.setCurrentPaper(request.paper());
        }
        if (request.seq() != null) {
            task.setCurrentSeq(request.seq());
        }
        if (request.total() != null) {
            task.setCurrentTotal(request.total());
        }
        if (request.score() != null && !request.score().isBlank()) {
            task.setScore(request.score());
        }
        if (request.message() != null && !request.message().isBlank()) {
            task.setMessage(request.message());
        }
        taskRepository.save(task);

        if (request.paper() != null && !request.paper().isBlank()) {
            upsertItem(task, request);
        }
        task.setDonePaper(countDone(taskId));
        taskRepository.save(task);

        broadcast(taskId);
        return OkResponse.of();
    }

    @Transactional
    public OkResponse finish(Long taskId, AgentFinishRequest request) {
        Task task = require(taskId);
        if (task.getStatus() == TaskStatus.CANCELLED) {
            // 任务已被中断：忽略 Agent 迟到的完成回传，保留中断态
            return OkResponse.of();
        }
        TaskStatus reported = parseTaskStatus(request.status());
        if (task.getStatus() == TaskStatus.PAUSED && reported == TaskStatus.FAILED) {
            // 用户暂停后 Agent 的迟到回传：失败态不得覆盖暂停态，
            // 否则任务会变成不可恢复的失败，暂停/继续链路被破坏
            return OkResponse.of();
        }
        task.setStatus(reported);
        if (request.score() != null && !request.score().isBlank()) {
            task.setScore(request.score());
        }
        if (request.summary() != null && !request.summary().isBlank()) {
            task.setMessage(request.summary());
        }
        task.setDonePaper(countDone(taskId));
        taskRepository.save(task);
        broadcast(taskId);
        return OkResponse.of();
    }

    /** Agent 交卷后回传结果页采集的正确答案，落到任务明细供导出。 */
    @Transactional
    public OkResponse recordAnswers(Long taskId, AgentAnswersRequest request) {
        Task task = require(taskId);
        if (request.paper() == null || request.paper().isBlank()) {
            return OkResponse.of();
        }
        TaskItem item = taskItemRepository
                .findFirstByTaskIdAndPaperName(task.getId(), request.paper())
                .orElseGet(() -> {
                    TaskItem created = new TaskItem();
                    created.setTaskId(task.getId());
                    created.setPaperName(request.paper());
                    created.setStatus(TaskItemStatus.PENDING);
                    paperRepository.findByBatchAndName(task.getBatch(), request.paper())
                            .ifPresent(paper -> created.setPaperId(paper.getId()));
                    return created;
                });
        if (request.examId() != null && !request.examId().isBlank()) {
            item.setExamId(request.examId());
        }
        try {
            item.setAnswersJson(objectMapper.writeValueAsString(
                    request.items() == null ? List.of() : request.items()));
        } catch (JsonProcessingException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "答案序列化失败: " + ex.getMessage());
        }
        taskItemRepository.save(item);
        // 答案社区：结果页新采集的完整答案自动发布为贡献（复用库内答案不计）
        communityService.record(task.getId(), task.getUserId(), task.getBatch(), request.paper(),
                request.items() == null ? 0 : request.items().size(), request.source());
        broadcast(task.getId());
        return OkResponse.of();
    }

    /** Agent 实时抓题后回传题目原文（含选项），供前端展示"题目完整信息"。 */
    @Transactional
    public OkResponse recordQuestions(Long taskId, AgentQuestionsRequest request) {
        Task task = require(taskId);
        if (request.paper() == null || request.paper().isBlank()) {
            return OkResponse.of();
        }
        TaskItem item = taskItemRepository
                .findFirstByTaskIdAndPaperName(task.getId(), request.paper())
                .orElseGet(() -> {
                    TaskItem created = new TaskItem();
                    created.setTaskId(task.getId());
                    created.setPaperName(request.paper());
                    created.setStatus(TaskItemStatus.PENDING);
                    paperRepository.findByBatchAndName(task.getBatch(), request.paper())
                            .ifPresent(paper -> created.setPaperId(paper.getId()));
                    return created;
                });
        if (request.examId() != null && !request.examId().isBlank()) {
            item.setExamId(request.examId());
        }
        try {
            item.setQuestionsJson(objectMapper.writeValueAsString(
                    request.questions() == null ? List.of() : request.questions()));
        } catch (JsonProcessingException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "题目序列化失败: " + ex.getMessage());
        }
        taskItemRepository.save(item);
        broadcast(task.getId());
        return OkResponse.of();
    }

    /** Agent 回传当前页面截图（dataURL），供"浏览器模式"查看雨课堂原始页面。 */
    public OkResponse updateFrame(Long taskId, AgentFrameRequest request) {
        require(taskId);
        if (request.image() == null || request.image().isBlank()) {
            return OkResponse.of();
        }
        frameCache.put(taskId, new TaskFrameResponse(
                request.image(), request.url(), System.currentTimeMillis()));
        return OkResponse.of();
    }

    /** 前端轮询拉取最近一帧实时截图；暂无截图时返回空图。 */
    public TaskFrameResponse getFrame(Long taskId) {
        require(taskId);
        TaskFrameResponse frame = frameCache.get(taskId);
        return frame == null ? new TaskFrameResponse(null, null, null) : frame;
    }

    /** 把任务下各试卷的题干与正确答案汇总为 Markdown。 */
    public String exportMarkdown(Long userId, Long id) {
        Task task = requireOwned(userId, id);
        StringBuilder sb = new StringBuilder();
        sb.append("# 任务 #").append(task.getId()).append(" 答案导出\n\n");
        sb.append("- 批次：").append(task.getBatch() == null ? "未指定" : task.getBatch()).append('\n');
        sb.append("- 模式：").append(task.getMode()).append('\n');
        sb.append("- 导出时间：").append(Instant.now()).append('\n');
        sb.append('\n');

        for (TaskItem item : taskItemRepository.findByTaskIdOrderByIdAsc(task.getId())) {
            sb.append("## ").append(item.getPaperName());
            if (item.getScore() != null && !item.getScore().isBlank()) {
                sb.append("（得分 ").append(item.getScore()).append("）");
            }
            sb.append("\n\n");

            List<AgentAnswersRequest.AnswerItem> answers = readAnswers(item.getAnswersJson());
            if (answers.isEmpty()) {
                sb.append("_未采集到答案_\n\n");
                continue;
            }
            for (AgentAnswersRequest.AnswerItem answer : answers) {
                sb.append("**").append(answer.seq() == null ? "" : answer.seq()).append(".** ")
                        .append(answer.stem() == null ? "" : answer.stem()).append('\n');
                sb.append("- 答案：").append(answer.answer() == null ? "" : answer.answer()).append("\n\n");
            }
        }
        return sb.toString();
    }

    private List<AgentAnswersRequest.AnswerItem> readAnswers(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (Exception ex) {
            log.warn("解析任务答案失败：{}", ex.getMessage());
            return List.of();
        }
    }

    private List<TaskItemSnapshot.QuestionView> readQuestions(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (Exception ex) {
            log.warn("解析题目信息失败：{}", ex.getMessage());
            return List.of();
        }
    }

    private void upsertItem(Task task, AgentProgressRequest request) {
        TaskItem item = taskItemRepository
                .findFirstByTaskIdAndPaperName(task.getId(), request.paper())
                .orElseGet(() -> {
                    TaskItem created = new TaskItem();
                    created.setTaskId(task.getId());
                    created.setPaperName(request.paper());
                    created.setStatus(TaskItemStatus.PENDING);
                    paperRepository.findByBatchAndName(task.getBatch(), request.paper())
                            .ifPresent(paper -> created.setPaperId(paper.getId()));
                    return created;
                });
        if (request.examId() != null && !request.examId().isBlank()) {
            item.setExamId(request.examId());
        }
        if (request.status() != null && !request.status().isBlank()) {
            item.setStatus(parseItemStatus(request.status()));
            // 失败原因落库：前端悬停可见、排查不用翻日志（此前 error 一直是 null，失败原因直接丢失）
            if ("FAILED".equalsIgnoreCase(request.status())
                    && request.message() != null && !request.message().isBlank()) {
                item.setError(request.message());
            }
        }
        if (request.score() != null && !request.score().isBlank()) {
            item.setScore(request.score());
        }
        taskItemRepository.save(item);
    }

    private int countDone(Long taskId) {
        return (int) taskItemRepository.findByTaskIdOrderByIdAsc(taskId).stream()
                .filter(item -> item.getStatus().isTerminal())
                .count();
    }

    private TaskSnapshot toSnapshot(Task task) {
        List<TaskItemSnapshot> items = taskItemRepository.findByTaskIdOrderByIdAsc(task.getId()).stream()
                .map(item -> new TaskItemSnapshot(item.getPaperName(), item.getExamId(),
                        item.getStatus().name(), item.getScore(), item.getError(),
                        readQuestions(item.getQuestionsJson())))
                .toList();
        return new TaskSnapshot(
                task.getId(),
                task.getBatch(),
                task.getMode(),
                task.getStatus().name(),
                task.getSessionId(),
                task.getTotalPaper(),
                task.getDonePaper(),
                task.getCurrentPaper(),
                task.getCurrentSeq(),
                task.getCurrentTotal(),
                task.getScore(),
                items,
                task.getMessage(),
                task.getCourseName(),
                readPapers(task.getPapersJson()),
                readRedo(task.getRedoJson()),
                task.getReuseYkt(),
                task.getCreatedAt());
    }

    private List<String> readRedo(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (IOException ex) {
            return List.of();
        }
    }

    private void broadcast(Long taskId) {
        webSocketHandler.broadcast(taskId, snapshot(taskId));
    }

    private Task require(Long id) {
        return taskRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "任务不存在"));
    }

    private Task requireOwned(Long userId, Long id) {
        Task task = require(id);
        if (!task.getUserId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权访问该任务");
        }
        return task;
    }

    private TaskStatus parseTaskStatus(String status) {
        try {
            return status == null || status.isBlank() ? TaskStatus.SUCCESS : TaskStatus.valueOf(status);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "非法的任务状态: " + status);
        }
    }

    private TaskItemStatus parseItemStatus(String status) {
        try {
            return TaskItemStatus.valueOf(status);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "非法的明细状态: " + status);
        }
    }
}
