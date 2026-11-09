package com.ykt.platform.controller;

import com.ykt.platform.dto.InviteInfoResponse;
import com.ykt.platform.dto.ChangePasswordRequest;
import com.ykt.platform.dto.ChangeUsernameRequest;
import com.ykt.platform.dto.LoginRequest;
import com.ykt.platform.dto.MeResponse;
import com.ykt.platform.dto.OkResponse;
import com.ykt.platform.dto.SetAvatarRequest;
import com.ykt.platform.dto.LoginResponse;
import com.ykt.platform.dto.RegisterRequest;
import com.ykt.platform.dto.RegisterResponse;
import com.ykt.platform.security.SecurityUtil;
import com.ykt.platform.service.AuthService;
import com.ykt.platform.service.InviteService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
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

    /** 当前登录用户信息（头像/最近登录），个人中心用。 */
    @GetMapping("/me")
    public MeResponse me() {
        return authService.me(SecurityUtil.currentUserId());
    }

    /** 修改用户名（登录名同步变更，改名后用新用户名登录）。 */
    @PutMapping("/username")
    public java.util.Map<String, Object> changeUsername(@RequestBody ChangeUsernameRequest request) {
        String newUsername = authService.changeUsername(SecurityUtil.currentUserId(), request);
        return java.util.Map.of("ok", true, "username", newUsername);
    }

    /** 本人修改密码（验证旧密码；其它设备登录态失效）。 */
    @PutMapping("/password")
    public OkResponse changePassword(@RequestBody ChangePasswordRequest request) {
        authService.changePassword(SecurityUtil.currentUserId(), request);
        return OkResponse.of();
    }

    /** 设置头像（base64 dataURL）。 */
    @PutMapping("/avatar")
    public OkResponse setAvatar(@RequestBody SetAvatarRequest request) {
        authService.setAvatar(SecurityUtil.currentUserId(), request);
        return OkResponse.of();
    }

    /** 我的登录记录（最近 50 条）。 */
    @GetMapping("/login-logs")
    public java.util.List<com.ykt.platform.entity.LoginLog> myLoginLogs() {
        return authService.myLoginLogs(SecurityUtil.currentUserId());
    }

    /** 公开头像图片（顶栏/管理后台展示，无需登录）。 */
    @GetMapping("/avatar/{userId}")
    public org.springframework.http.ResponseEntity<byte[]> avatarImage(@PathVariable Long userId) {
        return authService.avatarImage(userId);
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
