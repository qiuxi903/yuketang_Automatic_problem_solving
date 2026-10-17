package com.ykt.platform.controller;

import com.ykt.platform.dto.CreateSessionRequest;
import com.ykt.platform.dto.OkResponse;
import com.ykt.platform.dto.SessionDetailResponse;
import com.ykt.platform.dto.SessionResponse;
import com.ykt.platform.security.SecurityUtil;
import com.ykt.platform.service.SessionService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sessions")
public class SessionController {

    private final SessionService sessionService;

    public SessionController(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    @PostMapping
    public SessionResponse create(@RequestBody CreateSessionRequest request) {
        return sessionService.create(SecurityUtil.currentUserId(), request);
    }

    @GetMapping("/{id}")
    public SessionDetailResponse get(@PathVariable Long id) {
        return sessionService.get(SecurityUtil.currentUserId(), id);
    }

    @DeleteMapping("/{id}")
    public OkResponse close(@PathVariable Long id) {
        return sessionService.close(SecurityUtil.currentUserId(), id);
    }
}
