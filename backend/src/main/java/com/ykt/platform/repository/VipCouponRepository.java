package com.ykt.platform.repository;

import com.ykt.platform.entity.VipCoupon;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface VipCouponRepository extends JpaRepository<VipCoupon, Long> {

    Optional<VipCoupon> findByCode(String code);

    /** 折扣码下单/兑换码核销时加行锁，防并发超用。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from VipCoupon c where c.code = :code")
    Optional<VipCoupon> lockByCode(@Param("code") String code);

    List<VipCoupon> findAllByOrderByCreatedAtDesc();

    List<VipCoupon> findByCodeIn(java.util.Collection<String> codes);
}
