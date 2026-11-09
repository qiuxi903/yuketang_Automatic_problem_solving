package com.ykt.platform.repository;

import com.ykt.platform.entity.VipBatchPrice;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface VipBatchPriceRepository extends JpaRepository<VipBatchPrice, Long> {

    Optional<VipBatchPrice> findByBatch(String batch);

    List<VipBatchPrice> findAllByOrderByBatchAsc();

    long deleteByBatch(String batch);
}
