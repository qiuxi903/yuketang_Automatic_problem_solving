package com.ykt.platform.repository;

import com.ykt.platform.entity.VipOrder;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface VipOrderRepository extends JpaRepository<VipOrder, Long> {

    Optional<VipOrder> findByOrderNo(String orderNo);

    /** 监听回调按实付金额核销时加行锁，防止同一订单被并发回调重复核销。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from VipOrder o where o.orderNo = :orderNo")
    Optional<VipOrder> lockByOrderNo(@Param("orderNo") String orderNo);

    List<VipOrder> findByUserIdOrderByCreatedAtDesc(Long userId);

    List<VipOrder> findAllByOrderByCreatedAtDesc();

    /**
     * 免签监听自动核销的候选单：待支付、实付金额精确相等、创建时间在窗口内。
     * 撞单（多单同金额）时调用方按创建时间取最早。
     */
    List<VipOrder> findByStatusAndPayableFenAndCreatedAtAfterOrderByCreatedAtAsc(
            String status, int payableFen, Instant after);

    long countByStatus(String status);

    /** 过期待支付单（定时清理任务用）。 */
    List<VipOrder> findByStatusAndExpiresAtBefore(String status, Instant before);
}
