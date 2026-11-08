package com.ykt.platform.repository;

import com.ykt.platform.entity.VipEntitlement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface VipEntitlementRepository extends JpaRepository<VipEntitlement, Long> {

    List<VipEntitlement> findByUserIdAndRevokedFalse(Long userId);

    Optional<VipEntitlement> findFirstByUserIdAndKindAndTargetBatchAndRevokedFalseOrderByIdAsc(
            Long userId, String kind, String targetBatch);

    /** 月卡在期判定：取未撤销、未过期的最新一条。 */
    Optional<VipEntitlement> findFirstByUserIdAndKindAndRevokedFalseAndExpiresAtAfterOrderByExpiresAtDesc(
            Long userId, String kind, java.time.Instant now);

    List<VipEntitlement> findByRevokedFalse();

    Optional<VipEntitlement> findFirstByUserIdAndKindAndTargetBatchOrderByIdAsc(
            Long userId, String kind, String targetBatch);
}
