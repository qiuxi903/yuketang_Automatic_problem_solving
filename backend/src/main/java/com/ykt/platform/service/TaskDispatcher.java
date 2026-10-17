package com.ykt.platform.service;

import com.ykt.platform.entity.Task;
import com.ykt.platform.entity.TaskStatus;
import com.ykt.platform.repository.TaskRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 单线程调度器：把数据库中处于 PENDING 但不在内存队列里的任务补投进队列，
 * 保证任务创建后一定可被 Agent 领取（P0 无持久化队列，进程重启即丢）。
 * P1：由 Redis 队列 + 常驻 Worker 取代，并增加失败重试与超时回收。
 */
@Component
public class TaskDispatcher {

    private static final Logger log = LoggerFactory.getLogger(TaskDispatcher.class);

    private final TaskQueue taskQueue;
    private final TaskRepository taskRepository;
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "task-dispatcher");
                thread.setDaemon(true);
                return thread;
            });

    public TaskDispatcher(TaskQueue taskQueue, TaskRepository taskRepository) {
        this.taskQueue = taskQueue;
        this.taskRepository = taskRepository;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        scheduler.scheduleWithFixedDelay(this::reconcile, 0, 3, TimeUnit.SECONDS);
    }

    private void reconcile() {
        try {
            List<Task> pending = taskRepository.findByStatus(TaskStatus.PENDING);
            for (Task task : pending) {
                if (!taskQueue.contains(task.getId())) {
                    taskQueue.enqueue(task.getId());
                }
            }
        } catch (RuntimeException ex) {
            log.warn("任务队列对账失败：{}", ex.getMessage());
        }
    }

    @PreDestroy
    public void stop() {
        scheduler.shutdownNow();
    }
}
