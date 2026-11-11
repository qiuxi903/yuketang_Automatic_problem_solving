package com.ykt.platform.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * VIP 全局设置（单例行，固定 id=1，由 VipBootstrap 启动时种子）。
 * 金额一律用「分」存储，避免浮点误差；前端展示时除以 100。
 */
@Entity
@Table(name = "vip_settings")
public class VipSettings {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 单日（一个批次/日期）解锁价，单位分。 */
    @Column(nullable = false)
    private int datePassPriceFen = 500;

    /** 月卡价格，单位分（默认 20 元；存量库由 VipBootstrap 从旧默认 2900/1500 迁移）。 */
    @Column(nullable = false)
    private int monthPriceFen = 2000;

    /** 月卡有效天数。 */
    @Column(nullable = false)
    private int monthDays = 30;

    /** 当前收款渠道：LISTEN（免签监听）/ MANUAL（人工确认）/ XUNHU（虎皮椒）。 */
    @Column(nullable = false, length = 16)
    private String payChannel = "LISTEN";

    /** 个人微信收款码图片（base64 dataURL），用户扫码后手动输入精确金额支付。 */
    @Lob
    private String payQrWechat;

    /** 个人支付宝收款码图片（base64 dataURL）。 */
    @Lob
    private String payQrAlipay;

    /** 支付页展示的说明文字（到账时间、注意事项等）。 */
    @Column(length = 512)
    private String payNote;

    /** 监听回调令牌：pay-listener 与人工核销接口必须携带，防伪造到账回调。 */
    @Column(nullable = false, length = 64)
    private String listenToken;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public int getDatePassPriceFen() { return datePassPriceFen; }
    public void setDatePassPriceFen(int datePassPriceFen) { this.datePassPriceFen = datePassPriceFen; }
    public int getMonthPriceFen() { return monthPriceFen; }
    public void setMonthPriceFen(int monthPriceFen) { this.monthPriceFen = monthPriceFen; }
    public int getMonthDays() { return monthDays; }
    public void setMonthDays(int monthDays) { this.monthDays = monthDays; }
    public String getPayChannel() { return payChannel; }
    public void setPayChannel(String payChannel) { this.payChannel = payChannel; }
    public String getPayQrWechat() { return payQrWechat; }
    public void setPayQrWechat(String payQrWechat) { this.payQrWechat = payQrWechat; }
    public String getPayQrAlipay() { return payQrAlipay; }
    public void setPayQrAlipay(String payQrAlipay) { this.payQrAlipay = payQrAlipay; }
    public String getPayNote() { return payNote; }
    public void setPayNote(String payNote) { this.payNote = payNote; }
    public String getListenToken() { return listenToken; }
    public void setListenToken(String listenToken) { this.listenToken = listenToken; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
