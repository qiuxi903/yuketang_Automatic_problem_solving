package com.ykt.platform.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** 登录日志：成功与失败都记（IP / 归属地 / UA），供管理后台审计与「上次登录位置」展示。 */
@Entity
@Table(name = "login_log")
public class LoginLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false, length = 64)
    private String username;

    /** 客户端真实 IP（X-Real-IP / X-Forwarded-For 解析）。 */
    @Column(nullable = false, length = 64)
    private String ip;

    /** IP 归属地（省市 / 运营商；离线库缺失或外网 IP 解析失败时为「未知」）。 */
    @Column(length = 128)
    private String location;

    /** 浏览器/客户端 UA（截断存储）。 */
    @Column(length = 512)
    private String userAgent;

    @Column(nullable = false)
    private boolean success;

    /** 失败原因等补充信息。 */
    @Column(length = 128)
    private String message;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getIp() { return ip; }
    public void setIp(String ip) { this.ip = ip; }
    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }
    public String getUserAgent() { return userAgent; }
    public void setUserAgent(String userAgent) { this.userAgent = userAgent; }
    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
