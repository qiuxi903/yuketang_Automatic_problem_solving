package com.ykt.platform.repository;

import com.ykt.platform.entity.VipActivity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface VipActivityRepository extends JpaRepository<VipActivity, Long> {

    List<VipActivity> findAllByOrderByCreatedAtDesc();

    List<VipActivity> findByEnabledTrue();
}
