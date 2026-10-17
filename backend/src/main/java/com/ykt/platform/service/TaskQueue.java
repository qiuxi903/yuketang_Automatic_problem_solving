package com.ykt.platform.service;

/**
 * 任务队列抽象。P0 为进程内内存队列；P1 换成 Redis（LPUSH/BRPOP + 原子领取），
 * 实现类替换即可，调用方无需改动。
 */
public interface TaskQueue {

    void enqueue(Long taskId);

    /** 取出队首任务 id，队列为空返回 null。 */
    Long poll();

    boolean remove(Long taskId);

    boolean contains(Long taskId);

    int size();
}
