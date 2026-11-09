package com.ykt.platform.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** 管理操作审计日志：敏感后台操作全程留痕（谁在何时对谁做了什么）。 */
@Entity
@Table(name = "admin_audit_log")
public class AdminAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long adminId;

    @Column(nullable = false, length = 64)
    private String adminUsername;

    /** 操作类型：RESET_PASSWORD / DISABLE / ENABLE / CHANGE_ROLE / CHANGE_USERNAME / CONFIRM_ORDER / REJECT_ORDER / GRANT / UNBIND / DELETE_BATCH_PRICE / BACKUP 等。 */
    @Column(nullable = false, length = 32)
    private String action;

    /** 操作对象（用户名 / 订单号 / 批次等）。 */
    @Column(length = 128)
    private String target;

    /** 详情（新密码不记录，只记「已重置」；改用户名记 新旧名字）。 */
    @Column(length = 512)
    private String detail;

    /** 操作来源 IP。 */
    @Column(length = 64)
    private String ip;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getAdminId() { return adminId; }
    public void setAdminId(Long adminId) { this.adminId = adminId; }
    public String getAdminUsername() { return adminUsername; }
    public void setAdminUsername(String adminUsername) { this.adminUsername = adminUsername; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getTarget() { return target; }
    public void setTarget(String target) { this.target = target; }
    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
    public String getIp() { return ip; }
    public void setIp(String ip) { this.ip = ip; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
