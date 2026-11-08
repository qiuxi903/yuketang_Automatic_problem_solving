package com.ykt.platform.repository;

import com.ykt.platform.entity.RainBinding;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RainBindingRepository extends JpaRepository<RainBinding, Long> {

    Optional<RainBinding> findByUserId(Long userId);

    Optional<RainBinding> findByYktName(String yktName);

    List<RainBinding> findAllByOrderByBoundAtDesc();
}
