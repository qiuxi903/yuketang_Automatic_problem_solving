package com.ykt.platform.controller;

import com.ykt.platform.dto.BatchOptionsResponse;
import com.ykt.platform.dto.CreateTaskRequest;
import com.ykt.platform.dto.OkResponse;
import com.ykt.platform.dto.SetBatchRequest;
import com.ykt.platform.dto.TaskCreateResponse;
import com.ykt.platform.dto.TaskFrameResponse;
import com.ykt.platform.dto.TaskSnapshot;
import com.ykt.platform.security.SecurityUtil;
import com.ykt.platform.service.TaskService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private final TaskService taskService;

    public TaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    @PostMapping
    public TaskCreateResponse create(@RequestBody CreateTaskRequest request) {
        return taskService.create(SecurityUtil.currentUserId(), request);
    }

    @GetMapping
    public List<TaskSnapshot> list() {
        return taskService.list(SecurityUtil.currentUserId());
    }

    @GetMapping("/{id}")
    public TaskSnapshot get(@PathVariable Long id) {
        return taskService.get(SecurityUtil.currentUserId(), id);
    }

    /** 登录后按账号实际作业列出可选批次与覆盖率。 */
    @GetMapping("/{id}/batch-options")
    public BatchOptionsResponse batchOptions(@PathVariable Long id) {
        return taskService.batchOptions(SecurityUtil.currentUserId(), id);
    }

    /** 确认批次与作答模式，任务交回 Agent 继续作答。 */
    @PostMapping("/{id}/batch")
    public OkResponse setBatch(@PathVariable Long id, @RequestBody SetBatchRequest request) {
        return taskService.setBatch(SecurityUtil.currentUserId(), id, request.batch(), request.mode(), request.redoPapers());
    }

    @PostMapping("/{id}/pause")
    public OkResponse pause(@PathVariable Long id) {
        return taskService.pause(SecurityUtil.currentUserId(), id);
    }

    /** 浏览器模式：拉取当前任务最近一帧实时页面截图。 */
    @GetMapping("/{id}/frame")
    public TaskFrameResponse frame(@PathVariable Long id) {
        taskService.get(SecurityUtil.currentUserId(), id);
        return taskService.getFrame(id);
    }

    @PostMapping("/{id}/resume")
    public OkResponse resume(@PathVariable Long id) {
        return taskService.resume(SecurityUtil.currentUserId(), id);
    }

    /** 中断任务：停止后续执行，任务进入终端态「已中断」。 */
    @PostMapping("/{id}/interrupt")
    public OkResponse interrupt(@PathVariable Long id) {
        return taskService.interrupt(SecurityUtil.currentUserId(), id);
    }

    /** 删除任务：连同任务明细一并删除，不可恢复。 */
    @DeleteMapping("/{id}")
    public OkResponse delete(@PathVariable Long id) {
        return taskService.delete(SecurityUtil.currentUserId(), id);
    }

    @GetMapping("/{id}/export")
    public ResponseEntity<byte[]> export(@PathVariable Long id) {
        String markdown = taskService.exportMarkdown(SecurityUtil.currentUserId(), id);
        byte[] body = markdown.getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/markdown; charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"task-" + id + "-answers.md\"")
                .body(body);
    }
}
