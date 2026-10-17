package com.ykt.platform.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ykt.platform.dto.TaskSnapshot;
import com.ykt.platform.service.TaskService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按任务维度推送 Task 快照。用原生 WebSocket 而非 STOMP，前端只需一个 WebSocket 对象。
 */
@Component
public class TaskWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(TaskWebSocketHandler.class);

    private final Map<Long, Set<WebSocketSession>> sessionsByTask = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;
    private final TaskService taskService;

    public TaskWebSocketHandler(ObjectMapper objectMapper, @Lazy TaskService taskService) {
        this.objectMapper = objectMapper;
        this.taskService = taskService;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        Long taskId = resolveTaskId(session);
        if (taskId == null) {
            closeQuietly(session);
            return;
        }
        sessionsByTask.computeIfAbsent(taskId, k -> ConcurrentHashMap.newKeySet()).add(session);
        // 连接建立即回放当前快照，前端断线重连无需额外拉取
        try {
            send(session, taskService.snapshot(taskId));
        } catch (RuntimeException ex) {
            log.warn("推送初始快照失败 taskId={}", taskId, ex);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Long taskId = resolveTaskId(session);
        if (taskId != null) {
            Set<WebSocketSession> sessions = sessionsByTask.get(taskId);
            if (sessions != null) {
                sessions.remove(session);
                if (sessions.isEmpty()) {
                    sessionsByTask.remove(taskId);
                }
            }
        }
    }

    public void broadcast(Long taskId, TaskSnapshot snapshot) {
        Set<WebSocketSession> sessions = sessionsByTask.get(taskId);
        if (sessions == null || sessions.isEmpty()) {
            return;
        }
        String payload;
        try {
            payload = objectMapper.writeValueAsString(snapshot);
        } catch (IOException ex) {
            log.warn("序列化任务快照失败 taskId={}", taskId, ex);
            return;
        }
        for (WebSocketSession session : sessions) {
            try {
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage(payload));
                }
            } catch (IOException ex) {
                log.debug("推送失败，忽略 taskId={} sessionId={}", taskId, session.getId());
            }
        }
    }

    private void send(WebSocketSession session, TaskSnapshot snapshot) {
        try {
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(snapshot)));
        } catch (IOException ex) {
            log.debug("发送初始快照失败 sessionId={}", session.getId());
        }
    }

    private Long resolveTaskId(WebSocketSession session) {
        if (session.getUri() == null) {
            return null;
        }
        String path = session.getUri().getPath();
        int idx = path.lastIndexOf('/');
        if (idx < 0 || idx == path.length() - 1) {
            return null;
        }
        try {
            return Long.valueOf(path.substring(idx + 1));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private void closeQuietly(WebSocketSession session) {
        try {
            session.close(CloseStatus.BAD_DATA);
        } catch (IOException ignored) {
            // 关闭失败无需处理
        }
    }
}
