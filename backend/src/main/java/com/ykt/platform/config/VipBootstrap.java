package com.ykt.platform.config;

import com.ykt.platform.entity.User;
import com.ykt.platform.entity.VipAccount;
import com.ykt.platform.repository.UserRepository;
import com.ykt.platform.repository.VipAccountRepository;
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
    private final int freeQuotaOnRegister;

    public VipBootstrap(UserRepository userRepository,
                        VipAccountRepository accountRepository,
                        VipService vipService,
                        VipProperties properties) {
        this.userRepository = userRepository;
        this.accountRepository = accountRepository;
        this.vipService = vipService;
        this.freeQuotaOnRegister = properties.freeQuotaOnRegister();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void bootstrap() {
        // 单例设置行（幂等）
        vipService.settings();
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
