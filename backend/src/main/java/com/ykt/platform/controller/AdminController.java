package com.ykt.platform.controller;

import com.ykt.platform.dto.AdminOverviewResponse;
import com.ykt.platform.dto.AdminTaskView;
import com.ykt.platform.dto.AdminUserView;
import com.ykt.platform.dto.TaskSnapshot;
import com.ykt.platform.dto.UpdateUserRoleRequest;
import com.ykt.platform.service.AdminService;
import com.ykt.platform.service.TaskService;
import org.springframework.web.bind.annotation.GetMapping;
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

    public AdminController(AdminService adminService, TaskService taskService) {
        this.adminService = adminService;
        this.taskService = taskService;
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

    @GetMapping("/tasks/{id}")
    public TaskSnapshot task(@PathVariable Long id) {
        return taskService.snapshot(id);
    }
}
