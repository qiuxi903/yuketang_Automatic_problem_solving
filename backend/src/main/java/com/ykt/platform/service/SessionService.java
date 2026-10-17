package com.ykt.platform.service;

import com.ykt.platform.dto.CreateSessionRequest;
import com.ykt.platform.dto.OkResponse;
import com.ykt.platform.dto.SessionDetailResponse;
import com.ykt.platform.dto.SessionResponse;
import com.ykt.platform.entity.SessionStatus;
import com.ykt.platform.entity.YktSession;
import com.ykt.platform.repository.YktSessionRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;

@Service
public class SessionService {

    private final YktSessionRepository sessionRepository;

    public SessionService(YktSessionRepository sessionRepository) {
        this.sessionRepository = sessionRepository;
    }

    @Transactional
    public SessionResponse create(Long userId, CreateSessionRequest request) {
        YktSession session = new YktSession();
        session.setUserId(userId);
        session.setBatch(request.batch());
        session.setMode(request.mode() == null ? "answer" : request.mode());
        session.setStatus(SessionStatus.WAITING_SCAN);
        // 二维码有效窗口，前端据此展示倒计时；超时后由 Agent 回传 EXPIRED
        session.setExpireAt(Instant.now().plusSeconds(300));
        YktSession saved = sessionRepository.save(session);
        // 二维码由 Agent 领取任务后经 /api/agent/tasks/{id}/qrcode 回传，此处为 null
        return new SessionResponse(saved.getId(), saved.getStatus().name(), saved.getQrcode());
    }

    public SessionDetailResponse get(Long userId, Long id) {
        YktSession session = findOwned(userId, id);
        return new SessionDetailResponse(session.getId(), session.getStatus().name(),
                session.getQrcode(), session.getYktName(),
                session.getBatch(), session.getMode(),
                session.getExpireAt(), session.getCreatedAt());
    }

    @Transactional
    public OkResponse close(Long userId, Long id) {
        YktSession session = findOwned(userId, id);
        session.setStatus(SessionStatus.CLOSED);
        sessionRepository.save(session);
        // P1：此处需通知 Agent 关闭对应浏览器上下文并清理 Redis 中的 Cookie
        return OkResponse.of();
    }

    @Transactional
    public void updateQrcode(Long sessionId, String qrcode) {
        YktSession session = requireSession(sessionId);
        session.setQrcode(qrcode);
        sessionRepository.save(session);
    }

    @Transactional
    public void updateBatch(Long sessionId, String batch) {
        YktSession session = requireSession(sessionId);
        session.setBatch(batch);
        sessionRepository.save(session);
    }

    /** 模式后置：登录后确认作答模式时同步回填会话。 */
    @Transactional
    public void updateMode(Long sessionId, String mode) {
        if (mode == null || mode.isBlank()) {
            return;
        }
        YktSession session = requireSession(sessionId);
        session.setMode(mode);
        sessionRepository.save(session);
    }

    @Transactional
    public void updateStatus(Long sessionId, String status, String yktName) {
        YktSession session = requireSession(sessionId);
        if (status != null && !status.isBlank()) {
            session.setStatus(parseStatus(status));
        }
        if (yktName != null && !yktName.isBlank()) {
            session.setYktName(yktName);
        }
        sessionRepository.save(session);
    }

    public YktSession findOwned(Long userId, Long id) {
        YktSession session = requireSession(id);
        if (!session.getUserId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权访问该会话");
        }
        return session;
    }

    private YktSession requireSession(Long id) {
        return sessionRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在"));
    }

    private SessionStatus parseStatus(String status) {
        try {
            return SessionStatus.valueOf(status);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "非法的会话状态: " + status);
        }
    }
}
