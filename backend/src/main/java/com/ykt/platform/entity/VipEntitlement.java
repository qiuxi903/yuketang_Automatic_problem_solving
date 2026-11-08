package com.ykt.platform.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 用户 VIP 权益（做题资格的最终事实来源）。
 * DATE：解锁某个答案库批次（targetBatch），不过期（一次付费永久可用）；
 * MONTH：月卡，expiresAt 前所有批次可用（续费叠加，可存在多行，取最大 expiresAt 判定）。
 */
@Entity
@Table(name = "vip_entitlement")
public class VipEntitlement {

    public static final String KIND_DATE = "DATE";
    public static final String KIND_MONTH = "MONTH";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    /** DATE / MONTH。 */
    @Column(nullable = false, length = 16)
    private String kind;

    /** DATE 权益对应的批次；MONTH 为 null。 */
    @Column(length = 64)
    private String targetBatch;

    /** MONTH 的到期时间；DATE 为 null（永久）。 */
    private Instant expiresAt;

    /** 来源订单号（兑换码/后台发放为 null）。 */
    @Column(length = 32)
    private String sourceOrderNo;

    /** 发放操作人（管理员手工发放时记录，系统发放为 null）。 */
    private Long grantedBy;

    /** 管理员撤销标记（撤销不删行，保留审计痕迹）。 */
    @Column(nullable = false)
    private boolean revoked = false;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public String getTargetBatch() { return targetBatch; }
    public void setTargetBatch(String targetBatch) { this.targetBatch = targetBatch; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public String getSourceOrderNo() { return sourceOrderNo; }
    public void setSourceOrderNo(String sourceOrderNo) { this.sourceOrderNo = sourceOrderNo; }
    public Long getGrantedBy() { return grantedBy; }
    public void setGrantedBy(Long grantedBy) { this.grantedBy = grantedBy; }
    public boolean isRevoked() { return revoked; }
    public void setRevoked(boolean revoked) { this.revoked = revoked; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
