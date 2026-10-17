package com.ykt.platform.repository;

import com.ykt.platform.entity.TaskItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TaskItemRepository extends JpaRepository<TaskItem, Long> {

    List<TaskItem> findByTaskIdOrderByIdAsc(Long taskId);

    Optional<TaskItem> findFirstByTaskIdAndPaperName(Long taskId, String paperName);
}
