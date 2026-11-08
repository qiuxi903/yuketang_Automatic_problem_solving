package com.ykt.platform.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 用户 VIP 侧账户信息（当前仅免费解锁额度计数）。
 * 注册时按 ykt.vip.free-quota-on-register 赠送，后台可加/减；
 * 用户消耗 1 次额度 = 解锁任意一个未解锁批次。
 */
@Entity
@Table(name = "vip_account")
public class VipAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private Long userId;

    /** 剩余免费解锁次数。 */
    @Column(nullable = false)
    private int freeQuota = 0;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public int getFreeQuota() { return freeQuota; }
    public void setFreeQuota(int freeQuota) { this.freeQuota = freeQuota; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
