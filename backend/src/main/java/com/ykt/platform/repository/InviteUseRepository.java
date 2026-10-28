package com.ykt.platform.repository;

import com.ykt.platform.entity.InviteUse;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface InviteUseRepository extends JpaRepository<InviteUse, Long> {

    List<InviteUse> findByCodeInOrderByUsedAtDesc(Collection<String> codes);

    /** 随邀请码一并清理（调用方需开启事务）。 */
    long deleteByCodeIn(Collection<String> codes);
}
