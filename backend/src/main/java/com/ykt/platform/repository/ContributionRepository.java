package com.ykt.platform.repository;

import com.ykt.platform.entity.Contribution;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ContributionRepository extends JpaRepository<Contribution, Long> {

    List<Contribution> findTop50ByOrderByCreatedAtDesc();

    Optional<Contribution> findFirstByTaskIdAndPaper(Long taskId, String paper);
}
