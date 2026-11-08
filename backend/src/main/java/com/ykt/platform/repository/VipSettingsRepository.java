package com.ykt.platform.repository;

import com.ykt.platform.entity.VipSettings;
import org.springframework.data.jpa.repository.JpaRepository;

/** VIP 设置单例行（固定 id=1，见 VipBootstrap）。 */
public interface VipSettingsRepository extends JpaRepository<VipSettings, Long> {
}
