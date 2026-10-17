package com.ykt.platform.repository;

import com.ykt.platform.entity.YktSession;
import org.springframework.data.jpa.repository.JpaRepository;

public interface YktSessionRepository extends JpaRepository<YktSession, Long> {
}
