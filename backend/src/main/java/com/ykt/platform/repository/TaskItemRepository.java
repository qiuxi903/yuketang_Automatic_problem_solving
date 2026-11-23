package com.ykt.platform.repository;

import com.ykt.platform.entity.Task;
import com.ykt.platform.entity.TaskItem;
import com.ykt.platform.entity.TaskItemStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TaskItemRepository extends JpaRepository<TaskItem, Long> {

    List<TaskItem> findByTaskIdOrderByIdAsc(Long taskId);

    /** 满分节奏：按卷名统计该账号历史已交卷（SUBMITTED）次数，Agent 据此判断本次是第几遍作答。 */
    @Query("select ti.paperName, count(ti) from TaskItem ti, Task t "
            + "where ti.taskId = t.id and t.userId = :userId and ti.status = :status "
            + "group by ti.paperName")
    List<Object[]> countSubmittedGroupedByUser(@Param("userId") Long userId,
                                               @Param("status") TaskItemStatus status);

    Optional<TaskItem> findFirstByTaskIdAndPaperName(Long taskId, String paperName);
}
