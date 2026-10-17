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

@Entity
@Table(name = "task_item")
public class TaskItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long taskId;

    private Long paperId;

    @Column(nullable = false, length = 128)
    private String paperName;

    private String examId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private TaskItemStatus status = TaskItemStatus.PENDING;

    private String score;

    /** 匹配服务返回的选项索引数组，P0 仅透传存储。 */
    @Lob
    private String idxJson;

    /** Agent 实际勾选的选项数组。 */
    @Lob
    private String selJson;

    /** 交卷后从结果页采集的正确答案，[{seq, stem, answer}] 的 JSON，用于导出。 */
    @Lob
    private String answersJson;

    /** Agent 实时抓到的题目原文，[{seq, stem, options}] 的 JSON，用于展示题目完整信息。 */
    @Lob
    private String questionsJson;

    @Column(length = 512)
    private String error;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getTaskId() {
        return taskId;
    }

    public void setTaskId(Long taskId) {
        this.taskId = taskId;
    }

    public Long getPaperId() {
        return paperId;
    }

    public void setPaperId(Long paperId) {
        this.paperId = paperId;
    }

    public String getPaperName() {
        return paperName;
    }

    public void setPaperName(String paperName) {
        this.paperName = paperName;
    }

    public String getExamId() {
        return examId;
    }

    public void setExamId(String examId) {
        this.examId = examId;
    }

    public TaskItemStatus getStatus() {
        return status;
    }

    public void setStatus(TaskItemStatus status) {
        this.status = status;
    }

    public String getScore() {
        return score;
    }

    public void setScore(String score) {
        this.score = score;
    }

    public String getIdxJson() {
        return idxJson;
    }

    public void setIdxJson(String idxJson) {
        this.idxJson = idxJson;
    }

    public String getSelJson() {
        return selJson;
    }

    public void setSelJson(String selJson) {
        this.selJson = selJson;
    }

    public String getAnswersJson() {
        return answersJson;
    }

    public void setAnswersJson(String answersJson) {
        this.answersJson = answersJson;
    }

    public String getQuestionsJson() {
        return questionsJson;
    }

    public void setQuestionsJson(String questionsJson) {
        this.questionsJson = questionsJson;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }
}
