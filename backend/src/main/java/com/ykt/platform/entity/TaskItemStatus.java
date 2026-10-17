package com.ykt.platform.entity;

/** 任务明细状态，取值见接口契约第一节。 */
/** 取到答案但未做：已交卷试卷只读了结果页取答案，本任务未（重）作答。 */
public enum TaskItemStatus {
    PENDING, ANSWERING, SUBMITTED, FAILED, SKIPPED, REUSED, COLLECTED;

    public boolean isTerminal() {
        return this == SUBMITTED || this == FAILED || this == SKIPPED || this == REUSED;
    }
}
