package com.ykt.platform.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 答案社区贡献记录：Agent 从结果页采集到完整正确答案后自动发布的一条「谁贡献了哪份卷子」。
 * 按 taskId+paper 去重：同一任务的同一份卷子只记一条，避免重复采集刷新贡献流。
 */
@Entity
@Table(name = "answer_contribution")
public class Contribution {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    /** 贡献时的答案库批次；批次未确认时为空。 */
    @Column(length = 64)
    private String batch;

    @Column(nullable = false, length = 128)
    private String paper;

    /** 本次发布的题目数。 */
    @Column(nullable = false)
    private int questions;

    /** collect=结果页新采集；reuse=答案库已有直接复用。旧数据 null 按 collect 展示。 */
    @Column(length = 16)
    private String source;

    private Long taskId;

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

    public String getBatch() {
        return batch;
    }

    public void setBatch(String batch) {
        this.batch = batch;
    }

    public String getPaper() {
        return paper;
    }

    public void setPaper(String paper) {
        this.paper = paper;
    }

    public int getQuestions() {
        return questions;
    }

    public void setQuestions(int questions) {
        this.questions = questions;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public Long getTaskId() {
        return taskId;
    }

    public void setTaskId(Long taskId) {
        this.taskId = taskId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
