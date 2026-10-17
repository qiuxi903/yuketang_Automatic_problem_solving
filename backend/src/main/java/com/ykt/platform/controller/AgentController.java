package com.ykt.platform.controller;

import java.util.List;

import com.ykt.platform.dto.AgentAnswersRequest;
import com.ykt.platform.dto.AgentFinishRequest;
import com.ykt.platform.dto.AgentFrameRequest;
import com.ykt.platform.dto.AgentPapersRequest;
import com.ykt.platform.dto.AgentProgressRequest;
import com.ykt.platform.dto.AgentQrcodeRequest;
import com.ykt.platform.dto.AgentQuestionsRequest;
import com.ykt.platform.dto.AgentSessionStatusRequest;
import com.ykt.platform.dto.BatchDecisionResponse;
import com.ykt.platform.dto.OkResponse;
import com.ykt.platform.dto.TaskSnapshot;
import com.ykt.platform.service.TaskService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Agent 回传接口，仅供内部 Agent 轮询调用。P0 不校验内部令牌，P1 增加内部鉴权。
 */
@RestController
@RequestMapping("/api/agent/tasks")
public class AgentController {

    private final TaskService taskService;

    public AgentController(TaskService taskService) {
        this.taskService = taskService;
    }

    @GetMapping(value = "/running", produces = "application/json")
    public List<TaskSnapshot> running() {
        return taskService.runningTasks();
    }

    @GetMapping("/next")
    public ResponseEntity<TaskSnapshot> next() {
        TaskSnapshot snapshot = taskService.agentNext();
        return snapshot == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(snapshot);
    }

    @PostMapping("/{id}/qrcode")
    public OkResponse qrcode(@PathVariable Long id, @RequestBody AgentQrcodeRequest request) {
        return taskService.updateQrcode(id, request.qrcode());
    }

    @PostMapping("/{id}/session-status")
    public OkResponse sessionStatus(@PathVariable Long id, @RequestBody AgentSessionStatusRequest request) {
        return taskService.updateSessionStatus(id, request.status(), request.yktName());
    }

    /** 扫码登录后回传账号实际作业清单，任务转入待确认批次。 */
    @PostMapping("/{id}/papers")
    public OkResponse papers(@PathVariable Long id, @RequestBody AgentPapersRequest request) {
        return taskService.recordPapers(id, request);
    }

    /** Agent 轮询批次决策：batch 非空即继续作答。 */
    @GetMapping("/{id}/batch")
    public BatchDecisionResponse batch(@PathVariable Long id) {
        return taskService.batchDecision(id);
    }

    @PostMapping("/{id}/progress")
    public OkResponse progress(@PathVariable Long id, @RequestBody AgentProgressRequest request) {
        return taskService.updateProgress(id, request);
    }

    /** 实时抓题后回传题目原文（含选项），供前端展示题目完整信息。 */
    @PostMapping("/{id}/questions")
    public OkResponse questions(@PathVariable Long id, @RequestBody AgentQuestionsRequest request) {
        return taskService.recordQuestions(id, request);
    }

    /** 回传当前页面截图（dataURL），供"浏览器模式"查看雨课堂原始页面。 */
    @PostMapping("/{id}/frame")
    public OkResponse frame(@PathVariable Long id, @RequestBody AgentFrameRequest request) {
        return taskService.updateFrame(id, request);
    }

    @PostMapping("/{id}/answers")
    public OkResponse answers(@PathVariable Long id, @RequestBody AgentAnswersRequest request) {
        return taskService.recordAnswers(id, request);
    }

    @PostMapping("/{id}/finish")
    public OkResponse finish(@PathVariable Long id, @RequestBody AgentFinishRequest request) {
        return taskService.finish(id, request);
    }
}
