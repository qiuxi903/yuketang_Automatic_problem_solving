package com.ykt.platform.service;

import com.ykt.platform.dto.LoginRequest;
import com.ykt.platform.dto.LoginResponse;
import com.ykt.platform.dto.RegisterRequest;
import com.ykt.platform.dto.RegisterResponse;
import com.ykt.platform.entity.LoginLog;
import com.ykt.platform.entity.User;
import com.ykt.platform.repository.LoginLogRepository;
import com.ykt.platform.repository.UserRepository;
import com.ykt.platform.util.ClientIp;

import com.ykt.platform.security.JwtService;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AuthService {

    /** 用户名规则：2-20 位，中文/字母/数字/下划线。 */
    private static final java.util.regex.Pattern USERNAME_RE =
            java.util.regex.Pattern.compile("^[\u4e00-\u9fa5A-Za-z0-9_]{2,20}$");

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final InviteService inviteService;
    private final VipService vipService;
    private final LoginLogRepository loginLogRepository;
    private final IpRegionService ipRegionService;
    private final jakarta.servlet.http.HttpServletRequest httpRequest;

    /** 登录失败锁定：同 IP+用户名 10 分钟内 5 次失败即锁（防爆破，内存态重启即清）。 */
    private final java.util.Map<String, java.util.Deque<Long>> failedLogins = new java.util.concurrent.ConcurrentHashMap<>();
    private static final int FAIL_LIMIT = 5;
    private static final long FAIL_WINDOW_MS = 10 * 60 * 1000L;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder,
                       JwtService jwtService, InviteService inviteService, VipService vipService,
                       LoginLogRepository loginLogRepository, IpRegionService ipRegionService,
                       jakarta.servlet.http.HttpServletRequest httpRequest) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.inviteService = inviteService;
        this.vipService = vipService;
        this.loginLogRepository = loginLogRepository;
        this.ipRegionService = ipRegionService;
        this.httpRequest = httpRequest;
    }

    @Transactional
    public RegisterResponse register(RegisterRequest request) {
        String username = request.username() == null ? "" : request.username().trim();
        String password = request.password() == null ? "" : request.password();
        if (userRepository.existsByUsername(username)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "用户名已存在，换一个试试");
        }
        if (!USERNAME_RE.matcher(username).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "用户名需 2-20 位，只能包含中文、字母、数字或下划线");
        }
        if (username.matches("\\d+")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "用户名不能是纯数字，太简单容易被猜");
        }
        if (password.length() < 8) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "密码至少 8 位");
        }
        if (!password.matches("(?=.*[A-Za-z])(?=.*\\d).+")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "密码需同时包含字母和数字");
        }
        // 邀请码：必填且一码一用，通过即标记为已使用
        inviteService.consume(request.inviteCode() == null ? "" : request.inviteCode().trim(), username);
        User user = new User();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(password));
        // 注册入口只产生普通用户；管理员账号由 AdminBootstrap 引导创建
        user.setRole("USER");
        User saved = userRepository.save(user);
        // VIP：注册即建 VIP 账户并赠送免费体验额度（次数 = ykt.vip.free-quota-on-register）
        vipService.account(saved.getId());
        return new RegisterResponse(saved.getId(), saved.getUsername());
    }

    public LoginResponse login(LoginRequest request) {
        String ip = ClientIp.resolve(httpRequest);
        String location = ipRegionService.locate(ip);
        String username = request.username() == null ? "" : request.username().trim();
        // 失败锁定前置：锁定期内直接拒绝，不给继续验密的机会
        if (isLocked(ip, username)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "登录失败次数过多，请 10 分钟后再试");
        }
        User user = userRepository.findByUsername(username).orElse(null);
        if (user == null || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            recordLoginLog(null, username, ip, location, false, "用户名或密码错误");
            checkAndLockFailures(ip, username);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户名或密码错误");
        }
        if (user.isDisabled()) {
            recordLoginLog(user, username, ip, location, false, "账号已被禁用");
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "账号已被禁用，请联系管理员");
        }
        String role = user.getRole() == null || user.getRole().isBlank() ? "USER" : user.getRole();
        String token = jwtService.generate(user.getId(), user.getUsername(), role);
        // 最近登录信息 + 登录日志
        user.setLastLoginIp(ip);
        user.setLastLoginLocation(location);
        user.setLastLoginAt(java.time.Instant.now());
        userRepository.save(user);
        recordLoginLog(user, username, ip, location, true, null);
        return new LoginResponse(token, user.getUsername(), role);
    }

    private void recordLoginLog(User user, String username, String ip, String location,
                                boolean success, String message) {
        try {
            if (user == null) {
                // 查无此用户的失败不入库（避免垃圾数据）；爆破防护由内存锁定承担
                return;
            }
            LoginLog logEntry = new LoginLog();
            logEntry.setUserId(user.getId());
            logEntry.setUsername(username);
            logEntry.setIp(ip);
            logEntry.setLocation(location);
            logEntry.setUserAgent(nvl(httpRequest.getHeader("User-Agent")));
            logEntry.setSuccess(success);
            logEntry.setMessage(message);
            loginLogRepository.save(logEntry);
        } catch (Exception ex) {
            // 日志失败不影响登录主流程
        }
    }

    private static String nvl(String v) {
        return v == null ? "" : v.substring(0, Math.min(v.length(), 500));
    }

    private void checkAndLockFailures(String ip, String username) {
        String key = ip + "|" + username;
        long now = System.currentTimeMillis();
        java.util.Deque<Long> window = failedLogins.computeIfAbsent(key, k -> new java.util.ArrayDeque<>());
        synchronized (window) {
            while (!window.isEmpty() && now - window.peekFirst() > FAIL_WINDOW_MS) {
                window.pollFirst();
            }
            window.addLast(now);
        }
    }

    private boolean isLocked(String ip, String username) {
        String key = ip + "|" + username;
        long now = System.currentTimeMillis();
        java.util.Deque<Long> window = failedLogins.get(key);
        if (window == null) {
            return false;
        }
        synchronized (window) {
            while (!window.isEmpty() && now - window.peekFirst() > FAIL_WINDOW_MS) {
                window.pollFirst();
            }
            return window.size() >= FAIL_LIMIT;
        }
    }
}
