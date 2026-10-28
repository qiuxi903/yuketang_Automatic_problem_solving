package com.ykt.platform.controller;

import com.ykt.platform.dto.InviteInfoResponse;
import com.ykt.platform.dto.LoginRequest;
import com.ykt.platform.dto.LoginResponse;
import com.ykt.platform.dto.RegisterRequest;
import com.ykt.platform.dto.RegisterResponse;
import com.ykt.platform.service.AuthService;
import com.ykt.platform.service.InviteService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final InviteService inviteService;

    public AuthController(AuthService authService, InviteService inviteService) {
        this.authService = authService;
        this.inviteService = inviteService;
    }

    @PostMapping("/register")
    public RegisterResponse register(@Valid @RequestBody RegisterRequest request) {
        return authService.register(request);
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    /** 邀请码公开查询（无需登录）：邀请链接落地注册页时校验并展示剩余次数。 */
    @GetMapping("/invite-info")
    public InviteInfoResponse inviteInfo(@RequestParam String code) {
        return inviteService.inspect(code == null ? "" : code.trim());
    }
}
