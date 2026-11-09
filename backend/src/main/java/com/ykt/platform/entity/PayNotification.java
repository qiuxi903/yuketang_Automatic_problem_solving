package com.ykt.platform.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 到账记录池：pay-listener / 管理员粘贴的每笔收款先入池，
 * 到达时没匹配到待支付订单也不丢——用户点「我已支付，核实到账」时按金额精确比对核销。
 */
@Entity
@Table(name = "pay_notification")
public class PayNotification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** wechat / alipay / manual-paste / xunhu。 */
    @Column(nullable = false, length = 16)
    private String channel;

    /** 收款金额（分）。 */
    @Column(nullable = false)
    private int amountFen;

    /** 原始通知文本（审计用）。 */
    @Column(length = 512)
    private String raw;

    /** 收到通知的时间。 */
    @Column(nullable = false)
    private Instant receivedAt = Instant.now();

    /** 是否已被订单核销（自动匹配或用户申报核实）。 */
    @Column(nullable = false)
    private boolean consumed = false;

    /** 核销它的订单号（自动核销 / 申报核销都回填，便于对账）。 */
    @Column(length = 32)
    private String consumedByOrderNo;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }
    public int getAmountFen() { return amountFen; }
    public void setAmountFen(int amountFen) { this.amountFen = amountFen; }
    public String getRaw() { return raw; }
    public void setRaw(String raw) { this.raw = raw; }
    public Instant getReceivedAt() { return receivedAt; }
    public void setReceivedAt(Instant receivedAt) { this.receivedAt = receivedAt; }
    public boolean isConsumed() { return consumed; }
    public void setConsumed(boolean consumed) { this.consumed = consumed; }
    public String getConsumedByOrderNo() { return consumedByOrderNo; }
    public void setConsumedByOrderNo(String consumedByOrderNo) { this.consumedByOrderNo = consumedByOrderNo; }
}
