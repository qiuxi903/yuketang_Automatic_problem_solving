package com.ykt.platform.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 单日解锁的按批次（日期）单独定价：缺省日期用 VipSettings.datePassPriceFen（默认 3 元），
 * 本表存在记录的日期按记录价计。删除记录即恢复默认价。
 */
@Entity
@Table(name = "vip_batch_price")
public class VipBatchPrice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 答案库批次（如 10-30），唯一。 */
    @Column(nullable = false, unique = true, length = 64)
    private String batch;

    /** 该日期的单日解锁价（分）。 */
    @Column(nullable = false)
    private int priceFen;

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    /** 操作管理员（系统/迁移写入为 null）。 */
    private Long updatedBy;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getBatch() { return batch; }
    public void setBatch(String batch) { this.batch = batch; }
    public int getPriceFen() { return priceFen; }
    public void setPriceFen(int priceFen) { this.priceFen = priceFen; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public Long getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(Long updatedBy) { this.updatedBy = updatedBy; }
}
