package com.ykt.platform.service;

import com.ykt.platform.entity.AdminAuditLog;
import com.ykt.platform.repository.AdminAuditLogRepository;
import com.ykt.platform.util.ClientIp;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;

/**
 * 管理操作审计：敏感后台操作统一落库（谁/何时/对谁/做了什么/来源 IP）。
 * 记录失败不抛出（不影响主流程），仅打日志。
 */
@Service
public class AdminAuditService {

    private final AdminAuditLogRepository repository;

    public AdminAuditService(AdminAuditLogRepository repository) {
        this.repository = repository;
    }

    /** 记录一条管理操作审计（自动带当前请求的来源 IP）。 */
    @Transactional
    public void record(Long adminId, String adminUsername, String action, String target, String detail) {
        try {
            AdminAuditLog entry = new AdminAuditLog();
            entry.setAdminId(adminId);
            entry.setAdminUsername(adminUsername == null ? "?" : adminUsername);
            entry.setAction(action);
            entry.setTarget(target == null ? "" : target);
            entry.setDetail(detail == null ? "" : detail);
            var attrs = RequestContextHolder.getRequestAttributes();
            if (attrs instanceof ServletRequestAttributes sra) {
                entry.setIp(ClientIp.resolve(sra.getRequest()));
            }
            repository.save(entry);
        } catch (Exception ignored) {
        }
    }

    /** 最近审计日志（管理端查看）。 */
    @Transactional(readOnly = true)
    public List<AdminAuditLog> recent(int limit) {
        return repository.findTop200ByOrderByCreatedAtDesc()
                .stream()
                .limit(Math.max(1, Math.min(limit, 200)))
                .toList();
    }
}
