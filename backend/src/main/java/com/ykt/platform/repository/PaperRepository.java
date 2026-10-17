package com.ykt.platform.repository;

import com.ykt.platform.entity.Paper;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PaperRepository extends JpaRepository<Paper, Long> {

    Optional<Paper> findByBatchAndName(String batch, String name);

    long countByBatch(String batch);

    List<Paper> findByBatchOrderByNameAsc(String batch);
}
