package com.ykt.platform.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "task")
public class Task {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    private Long sessionId;

    /** 答案库批次；批次后置流程下，登录并确认批次前为 null。 */
    @Column(length = 64)
    private String batch;

    /** Agent 扫码登录后抓到的账号实际作业清单，[{name, questionCount, fullScore, score, status}]。 */
    @Lob
    private String papersJson;

    /** 重做任务：只做这些卷子（JSON 数组，卷名列表），为空表示普通任务。 */
    @Lob
    private String redoJson;

    /** 同账号续做提示：Agent 优先派给已登录该雨课堂账号的工人（免扫码）。 */
    @Column(length = 64)
    private String reuseYkt;

    /** 扫码登录后所在的雨课堂课程名，仅用于控制台展示账号上下文。 */
    @Column(length = 128)
    private String courseName;

    /** answer=自动作答，collect=答案采集（P1）。 */
    @Column(nullable = false, length = 16)
    private String mode = "answer";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private TaskStatus status = TaskStatus.PENDING;

    @Column(nullable = false)
    private int totalPaper;

    @Column(nullable = false)
    private int donePaper;

    private String currentPaper;

    private Integer currentSeq;

    private Integer currentTotal;

    private String score;

    @Column(length = 512)
    private String message;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getSessionId() {
        return sessionId;
    }

    public void setSessionId(Long sessionId) {
        this.sessionId = sessionId;
    }

    public String getBatch() {
        return batch;
    }

    public void setBatch(String batch) {
        this.batch = batch;
    }

    public String getPapersJson() {
        return papersJson;
    }

    public void setPapersJson(String papersJson) {
        this.papersJson = papersJson;
    }

    public String getRedoJson() {
        return redoJson;
    }

    public void setRedoJson(String redoJson) {
        this.redoJson = redoJson;
    }

    public String getReuseYkt() {
        return reuseYkt;
    }

    public void setReuseYkt(String reuseYkt) {
        this.reuseYkt = reuseYkt;
    }

    public String getCourseName() {
        return courseName;
    }

    public void setCourseName(String courseName) {
        this.courseName = courseName;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public TaskStatus getStatus() {
        return status;
    }

    public void setStatus(TaskStatus status) {
        this.status = status;
    }

    public int getTotalPaper() {
        return totalPaper;
    }

    public void setTotalPaper(int totalPaper) {
        this.totalPaper = totalPaper;
    }

    public int getDonePaper() {
        return donePaper;
    }

    public void setDonePaper(int donePaper) {
        this.donePaper = donePaper;
    }

    public String getCurrentPaper() {
        return currentPaper;
    }

    public void setCurrentPaper(String currentPaper) {
        this.currentPaper = currentPaper;
    }

    public Integer getCurrentSeq() {
        return currentSeq;
    }

    public void setCurrentSeq(Integer currentSeq) {
        this.currentSeq = currentSeq;
    }

    public Integer getCurrentTotal() {
        return currentTotal;
    }

    public void setCurrentTotal(Integer currentTotal) {
        this.currentTotal = currentTotal;
    }

    public String getScore() {
        return score;
    }

    public void setScore(String score) {
        this.score = score;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
