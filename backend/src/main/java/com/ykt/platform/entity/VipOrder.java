package com.ykt.platform.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * VIP 订单。免签监听方案下 payableFen = 应付价 − 折扣 + 随机尾数（1~99 分），
 * 监听回调按 payableFen 精确匹配自动核销（suffixFen 单独记录便于对账）。
 */
@Entity
@Table(name = "vip_order")
public class VipOrder {

    public static final String KIND_DATE_PASS = "DATE_PASS";
    public static final String KIND_MONTH_CARD = "MONTH_CARD";

    public static final String STATUS_CREATED = "CREATED";
    public static final String STATUS_PAID = "PAID";
    public static final String STATUS_CANCELLED = "CANCELLED";
    public static final String STATUS_REJECTED = "REJECTED";

    public static final String CHANNEL_LISTEN = "LISTEN";
    public static final String CHANNEL_MANUAL = "MANUAL";
    public static final String CHANNEL_XUNHU = "XUNHU";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 展示用订单号（时间戳 + 随机串），对内主键仍是 id。 */
    @Column(nullable = false, unique = true, length = 32)
    private String orderNo;

    @Column(nullable = false)
    private Long userId;

    /** DATE_PASS / MONTH_CARD。 */
    @Column(nullable = false, length = 16)
    private String kind;

    /** DATE_PASS 对应的答案库批次（如 10-30）；月卡为 null。 */
    @Column(length = 64)
    private String targetBatch;

    /** 原价（分）。 */
    @Column(nullable = false)
    private int originalFen;

    /** 优惠金额（分，活动与优惠码取最优后的让利）。 */
    @Column(nullable = false)
    private int discountFen;

    /** 随机尾数（1~99 分），0 表示无尾数（人工渠道）。 */
    @Column(nullable = false)
    private int suffixFen;

    /** 实付金额（分）= originalFen - discountFen + suffixFen。 */
    @Column(nullable = false)
    private int payableFen;

    /** CREATED / PAID / CANCELLED / REJECTED。 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private String status = STATUS_CREATED;

    /** 下单时的收款渠道快照（LISTEN / MANUAL / XUNHU）。 */
    @Column(nullable = false, length = 16)
    private String payChannel;

    /** 下单时使用的折扣优惠码（仅记录码值，核销时写入使用流水）。 */
    @Column(length = 32)
    private String couponCode;

    /** 用户留言（人工渠道可附转账截图说明）。 */
    @Column(length = 256)
    private String remark;

    /** 管理员备注（拒单原因等）。 */
    @Column(length = 256)
    private String adminRemark;

    /** 用户点「我已完成支付」的时间（仅人工/监听渠道的提示标记）。 */
    private Instant claimedAt;

    private Instant paidAt;

    /** 订单支付截止时间（超时自动视为过期，监听不再匹配）。 */
    @Column(nullable = false)
    private Instant expiresAt;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    public Long getId() { return id; }
    public String getOrderNo() { return orderNo; }
    public void setOrderNo(String orderNo) { this.orderNo = orderNo; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public String getTargetBatch() { return targetBatch; }
    public void setTargetBatch(String targetBatch) { this.targetBatch = targetBatch; }
    public int getOriginalFen() { return originalFen; }
    public void setOriginalFen(int originalFen) { this.originalFen = originalFen; }
    public int getDiscountFen() { return discountFen; }
    public void setDiscountFen(int discountFen) { this.discountFen = discountFen; }
    public int getSuffixFen() { return suffixFen; }
    public void setSuffixFen(int suffixFen) { this.suffixFen = suffixFen; }
    public int getPayableFen() { return payableFen; }
    public void setPayableFen(int payableFen) { this.payableFen = payableFen; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getPayChannel() { return payChannel; }
    public void setPayChannel(String payChannel) { this.payChannel = payChannel; }
    public String getCouponCode() { return couponCode; }
    public void setCouponCode(String couponCode) { this.couponCode = couponCode; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public String getAdminRemark() { return adminRemark; }
    public void setAdminRemark(String adminRemark) { this.adminRemark = adminRemark; }
    public Instant getClaimedAt() { return claimedAt; }
    public void setClaimedAt(Instant claimedAt) { this.claimedAt = claimedAt; }
    public Instant getPaidAt() { return paidAt; }
    public void setPaidAt(Instant paidAt) { this.paidAt = paidAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
