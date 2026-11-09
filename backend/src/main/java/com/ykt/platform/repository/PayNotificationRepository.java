package com.ykt.platform.repository;

import com.ykt.platform.entity.PayNotification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface PayNotificationRepository extends JpaRepository<PayNotification, Long> {

    /** 申报核实的候选：未核销、金额精确相等、收到时间不早于指定时刻（按收到时间升序取最早）。 */
    List<PayNotification> findByConsumedFalseAndAmountFenAndReceivedAtAfterOrderByReceivedAtAsc(
            int amountFen, Instant after);

    /** 管理端最近到账记录。 */
    List<PayNotification> findTop50ByOrderByReceivedAtDesc();
}
