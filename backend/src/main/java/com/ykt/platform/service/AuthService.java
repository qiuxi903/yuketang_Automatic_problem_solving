package com.ykt.platform.service;

import com.ykt.platform.dto.ChangePasswordRequest;
import com.ykt.platform.dto.ChangeUsernameRequest;
import com.ykt.platform.dto.ImportResult;
import com.ykt.platform.dto.ImportRow;
import com.ykt.platform.dto.LoginRequest;
import com.ykt.platform.dto.MeResponse;
import com.ykt.platform.dto.SetAvatarRequest;
import com.ykt.platform.dto.LoginResponse;
import com.ykt.platform.dto.RegisterRequest;
import com.ykt.platform.dto.RegisterResponse;
import com.ykt.platform.entity.LoginLog;
import com.ykt.platform.entity.User;
import com.ykt.platform.repository.LoginLogRepository;
import com.ykt.platform.repository.UserRepository;
import com.ykt.platform.util.ClientIp;

import java.util.List;

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
            java.util.regex.Pattern.compile("^[\u4e00-\u9fa5A-Za-z0-9_]{3,20}$");

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
                    "用户名需 3-20 位，只能包含中文、字母、数字或下划线");
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

    /** 管理员批量导入账号：免邀请码，逐条校验并创建（VIP 账户与注册同权赠送），返回每条结果。 */
    @Transactional
    public List<ImportResult> importUsers(List<ImportRow> rows) {
        List<ImportResult> results = new java.util.ArrayList<>();
        for (ImportRow row : rows == null ? List.<ImportRow>of() : rows) {
            String username = row.username() == null ? "" : row.username().trim();
            String password = row.password() == null ? "" : row.password();
            try {
                // 导入不限制用户名/密码的格式与长度（只有注册限制）；硬约束仅两条：不能为空、不能重名
                if (username.isEmpty() || password.isEmpty()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "用户名或密码为空");
                }
                if (userRepository.existsByUsername(username)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "用户名已存在");
                }
                User user = new User();
                user.setUsername(username);
                user.setPasswordHash(passwordEncoder.encode(password));
                user.setRole("USER");
                User saved = userRepository.save(user);
                vipService.account(saved.getId());
                results.add(new ImportResult(saved.getUsername(), true, "导入成功"));
            } catch (ResponseStatusException ex) {
                results.add(new ImportResult(username, false, ex.getReason()));
            } catch (Exception ex) {
                results.add(new ImportResult(username, false, "导入失败"));
            }
        }
        return results;
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

    // ---------- 账号中心（个人设置） ----------

    /** 当前用户信息（含头像）。 */
    @Transactional(readOnly = true)
    public MeResponse me(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户不存在"));
        return new MeResponse(user.getId(), user.getUsername(), roleOf(user), user.getAvatar(),
                user.getCreatedAt(), user.getLastLoginIp(), user.getLastLoginLocation(), user.getLastLoginAt());
    }

    /**
     * 修改用户名（登录名）：规则同注册、不可与现有用户重名。
     * 改名后 JwtAuthFilter 会用库里的最新用户名下发（约 30 秒内生效），登录名同步变更。
     */
    @Transactional
    public String changeUsername(Long userId, ChangeUsernameRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户不存在"));
        String newUsername = request.username() == null ? "" : request.username().trim();
        if (newUsername.equals(user.getUsername())) {
            return newUsername;
        }
        if (!USERNAME_RE.matcher(newUsername).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "用户名需 3-20 位，只能包含中文、字母、数字或下划线");
        }
        if (newUsername.matches("\\d+")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "用户名不能是纯数字，太简单容易被猜");
        }
        if (userRepository.existsByUsername(newUsername)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "用户名已存在，换一个试试");
        }
        user.setUsername(newUsername);
        userRepository.save(user);
        return newUsername;
    }

    /** 本人修改密码：验证旧密码；成功后其它设备的登录态失效。 */
    @Transactional
    public void changePassword(Long userId, ChangePasswordRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户不存在"));
        if (!passwordEncoder.matches(request.oldPassword(), user.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "旧密码不正确");
        }
        if (request.newPassword() == null || request.newPassword().length() < 8) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "新密码至少 8 位");
        }
        if (!request.newPassword().matches("(?=.*[A-Za-z])(?=.*\\d).+")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "新密码需同时包含字母和数字");
        }
        if (passwordEncoder.matches(request.newPassword(), user.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "新密码不能与旧密码相同");
        }
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        user.setPasswordUpdatedAt(java.time.Instant.now());
        userRepository.save(user);
    }

    /** 设置头像（base64 dataURL；传空清除）。 */
    @Transactional
    public void setAvatar(Long userId, SetAvatarRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户不存在"));
        String avatar = request.avatar();
        if (avatar != null && avatar.length() > 400_000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "头像太大（限制约 300KB），请换小图");
        }
        user.setAvatar(avatar == null || avatar.isBlank() ? null : avatar);
        userRepository.save(user);
    }

    /** 公开头像（管理端/顶栏展示），无头像返回 404。 */
    @Transactional(readOnly = true)
    public org.springframework.http.ResponseEntity<byte[]> avatarImage(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        String avatar = user.getAvatar();
        if (avatar == null || !avatar.startsWith("data:image/")) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND);
        }
        int comma = avatar.indexOf(',');
        String meta = avatar.substring(5, comma); // image/png;base64
        String contentType = meta.split(";")[0];
        byte[] bytes = java.util.Base64.getDecoder().decode(avatar.substring(comma + 1));
        return org.springframework.http.ResponseEntity.ok()
                .header("Cache-Control", "no-cache")
                .header("Content-Type", contentType)
                .body(bytes);
    }

    /** 我的登录记录（最近 50 条）。 */
    @Transactional(readOnly = true)
    public List<LoginLog> myLoginLogs(Long userId) {
        return loginLogRepository.findByUserIdOrderByCreatedAtDesc(userId,
                org.springframework.data.domain.PageRequest.of(0, 50));
    }

    private static String roleOf(User user) {
        return user.getRole() == null || user.getRole().isBlank() ? "USER" : user.getRole();
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
