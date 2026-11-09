package com.ykt.platform.controller;

import com.ykt.platform.dto.AdminOverviewResponse;
import com.ykt.platform.dto.AdminTaskView;
import com.ykt.platform.dto.AdminTaskView;
import com.ykt.platform.dto.AdminUserView;
import com.ykt.platform.entity.LoginLog;
import com.ykt.platform.dto.TaskSnapshot;
import com.ykt.platform.dto.UpdateUserRoleRequest;
import com.ykt.platform.service.AdminService;
import com.ykt.platform.service.TaskService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 管理后台接口，整个 /api/admin/** 前缀由 SecurityConfig 限定为 ADMIN。 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final AdminService adminService;
    private final TaskService taskService;
    private final com.ykt.platform.service.BackupService backupService;

    public AdminController(AdminService adminService, TaskService taskService,
                           com.ykt.platform.service.BackupService backupService) {
        this.adminService = adminService;
        this.taskService = taskService;
        this.backupService = backupService;
    }

    /** 手动触发一次 H2 在线备份（每日 03:00 也会自动备份，保留 7 份）。 */
    @PostMapping("/backup")
    public java.util.Map<String, Object> backup() throws java.io.IOException {
        return java.util.Map.of("ok", true, "path", backupService.backupNow());
    }

    @GetMapping("/overview")
    public AdminOverviewResponse overview() {
        return adminService.overview();
    }

    @GetMapping("/users")
    public List<AdminUserView> users() {
        return adminService.users();
    }

    @PutMapping("/users/{id}/role")
    public AdminUserView updateRole(@PathVariable Long id, @RequestBody UpdateUserRoleRequest request) {
        return adminService.updateRole(id, request);
    }

    @GetMapping("/tasks")
    public List<AdminTaskView> tasks() {
        return adminService.tasks();
    }

    /** 管理员重置用户密码（重置后该用户所有登录态失效）。 */
    @PutMapping("/users/{id}/password")
    public java.util.Map<String, Object> resetPassword(@PathVariable Long id,
                                                       @RequestBody java.util.Map<String, String> body) {
        adminService.resetPassword(id, body.get("password"));
        return java.util.Map.of("ok", true);
    }

    /** 封禁/解封用户账号。 */
    @PutMapping("/users/{id}/disabled")
    public java.util.Map<String, Object> setDisabled(@PathVariable Long id,
                                                     @RequestBody java.util.Map<String, Boolean> body) {
        boolean disabled = Boolean.TRUE.equals(body.get("disabled"));
        adminService.setDisabled(id, disabled);
        return java.util.Map.of("ok", true, "disabled", disabled);
    }

    /** 用户登录记录（最近 50 条：IP/归属地/UA/结果）。 */
    @GetMapping("/users/{id}/login-logs")
    public List<LoginLog> loginLogs(@PathVariable Long id) {
        return adminService.loginLogs(id);
    }

    /** 按用户过滤做题记录（用户详情页）。 */
    @GetMapping("/users/{id}/tasks")
    public List<AdminTaskView> tasksByUser(@PathVariable Long id) {
        return adminService.tasksByUser(id);
    }

    @GetMapping("/tasks/{id}")
    public TaskSnapshot task(@PathVariable Long id) {
        return taskService.snapshot(id);
    }
}
