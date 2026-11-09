package com.ykt.platform.repository;

import com.ykt.platform.entity.LoginLog;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LoginLogRepository extends JpaRepository<LoginLog, Long> {

    List<LoginLog> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    List<LoginLog> findTop100ByOrderByCreatedAtDesc();
}
