package com.ykt.platform.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * VIP 优惠码/兑换码（结构与邀请码三件套同构）。
 * kind：
 *  DISCOUNT   折扣码——下单时填入，与活动折扣取最优；
 *  DATE_PASS  单日兑换码——兑换即解锁指定批次（可当「单日卡密」对外销售）；
 *  MONTH_PASS 月卡兑换码——兑换即得月卡（可当「月卡卡密」对外销售）；
 *  QUOTA      额度码——兑换即加 N 次免费解锁额度。
 */
@Entity
@Table(name = "vip_coupon")
public class VipCoupon {

    public static final String KIND_DISCOUNT = "DISCOUNT";
    public static final String KIND_DATE_PASS = "DATE_PASS";
    public static final String KIND_MONTH_PASS = "MONTH_PASS";
    public static final String KIND_QUOTA = "QUOTA";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 32)
    private String code;

    /** DISCOUNT / DATE_PASS / MONTH_PASS / QUOTA。 */
    @Column(nullable = false, length = 16)
    private String kind;

    /** 折扣码的折扣百分数（80 = 8 折），仅 DISCOUNT 有效。 */
    private Integer valuePercent;

    /** 额度码赠送的免费解锁次数，仅 QUOTA 有效。 */
    private Integer quotaCount;

    /** 折扣码适用商品：DATE / MONTH / ALL。 */
    @Column(length = 16)
    private String appliesTo;

    /** 最大使用次数；0 = 不限。 */
    @Column(nullable = false)
    private int maxUses = 1;

    /** 单个用户最多使用次数（默认 1，防一人刷码）。 */
    @Column(nullable = false)
    private int perUserLimit = 1;

    private Integer usedCount = 0;

    @Column(nullable = false)
    private boolean disabled = false;

    /** 过期时间；null 永不过期。 */
    private Instant expiresAt;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    private Long createdByUserId;

    public Long getId() { return id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public Integer getValuePercent() { return valuePercent; }
    public void setValuePercent(Integer valuePercent) { this.valuePercent = valuePercent; }
    public Integer getQuotaCount() { return quotaCount; }
    public void setQuotaCount(Integer quotaCount) { this.quotaCount = quotaCount; }
    public String getAppliesTo() { return appliesTo; }
    public void setAppliesTo(String appliesTo) { this.appliesTo = appliesTo; }
    public int getMaxUses() { return maxUses; }
    public void setMaxUses(int maxUses) { this.maxUses = maxUses; }
    public int getPerUserLimit() { return perUserLimit; }
    public void setPerUserLimit(int perUserLimit) { this.perUserLimit = perUserLimit; }
    public Integer getUsedCount() { return usedCount; }
    public void setUsedCount(Integer usedCount) { this.usedCount = usedCount; }
    public boolean isDisabled() { return disabled; }
    public void setDisabled(boolean disabled) { this.disabled = disabled; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Long getCreatedByUserId() { return createdByUserId; }
    public void setCreatedByUserId(Long createdByUserId) { this.createdByUserId = createdByUserId; }

    public int usedCountOrDefault() {
        return usedCount == null ? 0 : Math.max(0, usedCount);
    }

    /** 剩余可用次数；不限次数返回 -1。 */
    public int remainingUses() {
        if (maxUses == 0) return -1;
        return Math.max(0, maxUses - usedCountOrDefault());
    }

    public boolean exhausted() {
        return maxUses != 0 && usedCountOrDefault() >= maxUses;
    }

    /** 当前是否可用（未禁用、未用尽、未过期）。 */
    public boolean usableAt(Instant now) {
        if (disabled || exhausted()) return false;
        return expiresAt == null || !now.isAfter(expiresAt);
    }
}
