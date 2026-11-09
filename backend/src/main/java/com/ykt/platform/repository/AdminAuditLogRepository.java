package com.ykt.platform.repository;

import com.ykt.platform.entity.AdminAuditLog;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AdminAuditLogRepository extends JpaRepository<AdminAuditLog, Long> {

    List<AdminAuditLog> findByTargetContainingOrderByCreatedAtDesc(String target, Pageable pageable);

    List<AdminAuditLog> findTop200ByOrderByCreatedAtDesc();
}
