package com.ykt.platform.config;

import com.ykt.platform.ws.TaskWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final TaskWebSocketHandler taskWebSocketHandler;

    public WebSocketConfig(TaskWebSocketHandler taskWebSocketHandler) {
        this.taskWebSocketHandler = taskWebSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // 路径为 /ws/tasks/{id}，用通配注册后在 handler 内解析 taskId
        registry.addHandler(taskWebSocketHandler, "/ws/tasks/*")
                .setAllowedOriginPatterns("http://localhost:5173", "http://localhost:*");
    }
}
