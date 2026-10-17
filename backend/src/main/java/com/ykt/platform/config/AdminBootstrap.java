package com.ykt.platform.config;

import com.ykt.platform.entity.User;
import com.ykt.platform.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 启动时引导管理员账号：不存在则按配置创建，存在则确保其角色为 ADMIN。
 * 账号密码可通过 ykt.admin.username / ykt.admin.password 覆盖（默认 admin / admin123）。
 */
@Component
public class AdminBootstrap {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final String username;
    private final String password;

    public AdminBootstrap(UserRepository userRepository,
                          PasswordEncoder passwordEncoder,
                          @Value("${ykt.admin.username:admin}") String username,
                          @Value("${ykt.admin.password:admin123}") String password) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.username = username;
        this.password = password;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void bootstrap() {
        if (username == null || username.isBlank()) {
            return;
        }
        User admin = userRepository.findByUsername(username).orElseGet(() -> {
            User created = new User();
            created.setUsername(username);
            created.setPasswordHash(passwordEncoder.encode(password));
            created.setRole("ADMIN");
            log.info("已创建管理员账号：{}", username);
            return created;
        });
        if (!"ADMIN".equalsIgnoreCase(admin.getRole())) {
            admin.setRole("ADMIN");
            log.info("已将账号 {} 提升为管理员", username);
        }
        userRepository.save(admin);
    }
}
