package com.ykt.platform.repository;

import com.ykt.platform.entity.VipAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface VipAccountRepository extends JpaRepository<VipAccount, Long> {

    Optional<VipAccount> findByUserId(Long userId);
}
