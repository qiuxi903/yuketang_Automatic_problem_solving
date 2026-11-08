package com.ykt.platform.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 限时折扣活动：窗口期内下单自动享受折扣（与优惠码取最优，不叠加）。
 * discountPercent = 折扣百分数，80 表示 8 折（原价的 80%）。
 */
@Entity
@Table(name = "vip_activity")
public class VipActivity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String name;

    /** 折扣百分数：80 = 8 折；范围 [1, 99]。 */
    @Column(nullable = false)
    private int discountPercent = 80;

    /** 适用的商品：DATE（单日解锁）/ MONTH（月卡）/ ALL。 */
    @Column(nullable = false, length = 16)
    private String appliesTo = "ALL";

    /** 生效窗口；null 表示该端开放。 */
    private Instant startsAt;

    private Instant endsAt;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public int getDiscountPercent() { return discountPercent; }
    public void setDiscountPercent(int discountPercent) { this.discountPercent = discountPercent; }
    public String getAppliesTo() { return appliesTo; }
    public void setAppliesTo(String appliesTo) { this.appliesTo = appliesTo; }
    public Instant getStartsAt() { return startsAt; }
    public void setStartsAt(Instant startsAt) { this.startsAt = startsAt; }
    public Instant getEndsAt() { return endsAt; }
    public void setEndsAt(Instant endsAt) { this.endsAt = endsAt; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    /** 当前时刻是否在生效窗口内（含端点；null 端视为开放）。 */
    public boolean activeAt(Instant now) {
        if (!enabled) return false;
        if (startsAt != null && now.isBefore(startsAt)) return false;
        if (endsAt != null && now.isAfter(endsAt)) return false;
        return true;
    }

    /** 是否适用于指定商品（DATE / MONTH）。 */
    public boolean appliesToKind(String orderKind) {
        return "ALL".equalsIgnoreCase(appliesTo)
                || appliesTo.equalsIgnoreCase(orderKind);
    }
}
