package com.ykt.platform.service;

import org.springframework.stereotype.Component;

import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * P0 内存实现。P1 替换为 RedisTaskQueue（Redis List + 原子弹出），并支持多实例消费。
 */
@Component
public class InMemoryTaskQueue implements TaskQueue {

    private final Queue<Long> queue = new ConcurrentLinkedQueue<>();
    private final Set<Long> present = ConcurrentHashMap.newKeySet();

    @Override
    public void enqueue(Long taskId) {
        if (present.add(taskId)) {
            queue.offer(taskId);
        }
    }

    @Override
    public Long poll() {
        Long taskId = queue.poll();
        if (taskId != null) {
            present.remove(taskId);
        }
        return taskId;
    }

    @Override
    public boolean remove(Long taskId) {
        present.remove(taskId);
        return queue.remove(taskId);
    }

    @Override
    public boolean contains(Long taskId) {
        return present.contains(taskId);
    }

    @Override
    public int size() {
        return queue.size();
    }
}
