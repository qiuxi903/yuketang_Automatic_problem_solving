package com.ykt.platform.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** 注册邀请码：管理员生成，注册必填；maxUses>1 时一个码可邀请多人（0 = 不限次数）。 */
@Entity
@Table(name = "invite_code")
public class InviteCode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 32)
    private String code;

    /** 允许注册的最大次数；0 = 不限。旧数据（一码一用时期）为 null，按 1 处理。 */
    private Integer maxUses;

    /** 已使用次数。旧数据为 null，按 used ? 1 : 0 处理。 */
    private Integer usedCount;

    /** 已用尽（usedCount >= maxUses）。旧字段沿用为「已用尽」语义，避免历史数据迁移。 */
    @Column(nullable = false)
    private boolean used;

    /** 最近一次使用者（完整使用记录见 InviteUse）。 */
    @Column(length = 64)
    private String usedBy;

    /** 最近一次使用时间。 */
    private Instant usedAt;

    @Column(nullable = false)
    private boolean disabled;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    private Long createdByUserId;

    /** 生效的最大使用次数：未设置或非法值按 1（一码一用）处理。 */
    public int maxUsesOrDefault() {
        return maxUses == null || maxUses < 0 ? 1 : maxUses;
    }

    public int usedCountOrDefault() {
        return usedCount == null ? (used ? 1 : 0) : Math.max(0, usedCount);
    }

    /** 剩余可用次数；不限次数返回 -1。 */
    public int remainingUses() {
        int max = maxUsesOrDefault();
        if (max == 0) return -1;
        return Math.max(0, max - usedCountOrDefault());
    }

    /** 是否已用尽：不限次数的码永不「用尽」。 */
    public boolean exhausted() {
        int max = maxUsesOrDefault();
        return max != 0 && usedCountOrDefault() >= max;
    }

    public Long getId() { return id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public Integer getMaxUses() { return maxUses; }
    public void setMaxUses(Integer maxUses) { this.maxUses = maxUses; }
    public Integer getUsedCount() { return usedCount; }
    public void setUsedCount(Integer usedCount) { this.usedCount = usedCount; }
    public boolean isUsed() { return used; }
    public void setUsed(boolean used) { this.used = used; }
    public String getUsedBy() { return usedBy; }
    public void setUsedBy(String usedBy) { this.usedBy = usedBy; }
    public Instant getUsedAt() { return usedAt; }
    public void setUsedAt(Instant usedAt) { this.usedAt = usedAt; }
    public boolean isDisabled() { return disabled; }
    public void setDisabled(boolean disabled) { this.disabled = disabled; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Long getCreatedByUserId() { return createdByUserId; }
    public void setCreatedByUserId(Long createdByUserId) { this.createdByUserId = createdByUserId; }
}
