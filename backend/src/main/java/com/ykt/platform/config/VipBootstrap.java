package com.ykt.platform.config;

import com.ykt.platform.entity.User;
import com.ykt.platform.entity.VipAccount;
import com.ykt.platform.repository.UserRepository;
import com.ykt.platform.repository.VipAccountRepository;
import com.ykt.platform.repository.VipSettingsRepository;
import com.ykt.platform.service.VipService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * VIP 启动种子：设置单例行 + 为所有存量用户补建 VIP 账户（按 freeQuotaOnRegister 赠送额度，
 * 与新注册用户同权）。注册时赠送逻辑在 VipService.account() 的懒创建里。
 */
@Component
public class VipBootstrap {

    private static final Logger log = LoggerFactory.getLogger(VipBootstrap.class);

    private final UserRepository userRepository;
    private final VipAccountRepository accountRepository;
    private final VipService vipService;
    private final VipSettingsRepository settingsRepository;
    private final int freeQuotaOnRegister;

    public VipBootstrap(UserRepository userRepository,
                        VipAccountRepository accountRepository,
                        VipService vipService,
                        VipProperties properties,
                        VipSettingsRepository settingsRepository) {
        this.userRepository = userRepository;
        this.accountRepository = accountRepository;
        this.vipService = vipService;
        this.settingsRepository = settingsRepository;
        this.freeQuotaOnRegister = properties.freeQuotaOnRegister();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void bootstrap() {
        // 单例设置行（幂等）+ 旧默认价一次性迁移（仅当值仍是旧默认时才动，不覆盖管理员改过的价）：
        // 单日 300 -> 500；月卡 2900/1500 -> 2000
        var settings = vipService.settings();
        boolean migrated = false;
        if (settings.getDatePassPriceFen() == 300) {
            settings.setDatePassPriceFen(500);
            log.info("单日解锁默认价已从 300 分迁移为 500 分");
            migrated = true;
        }
        if (settings.getMonthPriceFen() == 2900 || settings.getMonthPriceFen() == 1500) {
            settings.setMonthPriceFen(2000);
            log.info("月卡默认价已迁移为 2000 分");
            migrated = true;
        }
        if (migrated) {
            settingsRepository.save(settings);
        }
        // 存量用户补建 VIP 账户，享受与新注册用户相同的免费体验额度
        List<User> users = userRepository.findAll();
        int created = 0;
        for (User user : users) {
            if (accountRepository.findByUserId(user.getId()).isEmpty()) {
                VipAccount account = new VipAccount();
                account.setUserId(user.getId());
                account.setFreeQuota(freeQuotaOnRegister);
                account.setCreatedAt(Instant.now());
                account.setUpdatedAt(Instant.now());
                accountRepository.save(account);
                created++;
            }
        }
        if (created > 0) {
            log.info("VIP 种子：为 {} 个存量用户补建账户（各赠 {} 次免费额度）", created, freeQuotaOnRegister);
        }
    }
}
