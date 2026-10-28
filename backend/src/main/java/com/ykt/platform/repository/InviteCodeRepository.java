package com.ykt.platform.repository;

import com.ykt.platform.entity.InviteCode;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface InviteCodeRepository extends JpaRepository<InviteCode, Long> {

    Optional<InviteCode> findByCode(String code);

    /** 注册消费邀请码时加行锁，避免并发注册把同一个「仅剩一次」的码用超。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from InviteCode i where i.code = :code")
    Optional<InviteCode> lockByCode(@Param("code") String code);

    List<InviteCode> findAllByOrderByCreatedAtDesc();

    /** 批量操作按码取实体。 */
    List<InviteCode> findByCodeIn(java.util.Collection<String> codes);

    List<InviteCode> findByUsedTrue();

    long countByUsedTrue();

    /** 物理删除全部已用尽的邀请码（调用方需开启事务），返回删除数量。 */
    long deleteByUsedTrue();
}
