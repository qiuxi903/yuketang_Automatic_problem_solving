package com.ykt.platform.repository;

import com.ykt.platform.entity.VipCouponUse;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface VipCouponUseRepository extends JpaRepository<VipCouponUse, Long> {

    long countByCodeAndUserId(String code, Long userId);

    List<VipCouponUse> findByCodeInOrderByUsedAtDesc(List<String> codes);

    long deleteByCodeIn(List<String> codes);

    long countByCode(String code);
}
