package com.ykt.platform.repository;

import com.ykt.platform.entity.InviteCode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface InviteCodeRepository extends JpaRepository<InviteCode, Long> {

    Optional<InviteCode> findByCode(String code);

    List<InviteCode> findAllByOrderByCreatedAtDesc();

    long countByUsedTrue();

    /** 物理删除全部已使用的邀请码（调用方需开启事务），返回删除数量。 */
    long deleteByUsedTrue();
}
