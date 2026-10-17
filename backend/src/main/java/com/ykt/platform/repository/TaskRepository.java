package com.ykt.platform.repository;

import com.ykt.platform.entity.Task;
import com.ykt.platform.entity.TaskStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface TaskRepository extends JpaRepository<Task, Long> {

    List<Task> findByUserIdOrderByIdDesc(Long userId);

    List<Task> findByStatus(TaskStatus status);

    List<Task> findByStatusIn(Collection<TaskStatus> statuses);

    Optional<Task> findFirstByStatusOrderByIdAsc(TaskStatus status);

    List<Task> findByUserIdAndStatusIn(Long userId, Collection<TaskStatus> statuses);
}
