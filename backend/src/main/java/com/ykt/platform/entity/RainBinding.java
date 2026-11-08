package com.ykt.platform.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 雨课堂账号绑定（1:1）：平台账号 ↔ 雨课堂账号名。
 * 首次扫码登录自动绑定；此后该平台账号只能扫绑定的雨课堂账号，该雨课堂账号也不能被
 * 其他平台账号绑定——防止拿付费账号替别人做题。解绑必须由管理员在后台操作。
 */
@Entity
@Table(name = "rain_binding")
public class RainBinding {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private Long userId;

    @Column(nullable = false, unique = true, length = 64)
    private String yktName;

    /** 是否由用户首次扫码自动绑定（管理员手工登记为 false）。 */
    @Column(nullable = false)
    private boolean autoBound = true;

    @Column(nullable = false)
    private Instant boundAt = Instant.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getYktName() { return yktName; }
    public void setYktName(String yktName) { this.yktName = yktName; }
    public boolean isAutoBound() { return autoBound; }
    public void setAutoBound(boolean autoBound) { this.autoBound = autoBound; }
    public Instant getBoundAt() { return boundAt; }
    public void setBoundAt(Instant boundAt) { this.boundAt = boundAt; }
}
