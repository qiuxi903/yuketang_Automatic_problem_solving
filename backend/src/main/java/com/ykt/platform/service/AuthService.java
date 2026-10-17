package com.ykt.platform.service;

import com.ykt.platform.dto.LoginRequest;
import com.ykt.platform.dto.LoginResponse;
import com.ykt.platform.dto.RegisterRequest;
import com.ykt.platform.dto.RegisterResponse;
import com.ykt.platform.entity.User;
import com.ykt.platform.repository.UserRepository;

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

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder,
                       JwtService jwtService, InviteService inviteService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.inviteService = inviteService;
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
        return new RegisterResponse(saved.getId(), saved.getUsername());
    }

    public LoginResponse login(LoginRequest request) {
        User user = userRepository.findByUsername(request.username())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户名或密码错误"));
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户名或密码错误");
        }
        String role = user.getRole() == null || user.getRole().isBlank() ? "USER" : user.getRole();
        String token = jwtService.generate(user.getId(), user.getUsername(), role);
        return new LoginResponse(token, user.getUsername(), role);
    }
}
