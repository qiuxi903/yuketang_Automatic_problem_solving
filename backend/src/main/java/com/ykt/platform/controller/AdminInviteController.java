package com.ykt.platform.controller;

import com.ykt.platform.dto.InviteBatchRequest;
import com.ykt.platform.dto.InviteCreateRequest;
import com.ykt.platform.dto.InviteCreateResponse;
import com.ykt.platform.dto.InviteView;
import com.ykt.platform.service.InviteService;
import com.ykt.platform.security.SecurityUtil;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 邀请码管理（仅管理员）。 */
@RestController
@RequestMapping("/api/admin/invites")
public class AdminInviteController {

    private final InviteService inviteService;

    public AdminInviteController(InviteService inviteService) {
        this.inviteService = inviteService;
    }

    @GetMapping
    public List<InviteView> list() {
        return inviteService.list();
    }

    @PostMapping
    public InviteCreateResponse generate(@RequestBody InviteCreateRequest request) {
        Long adminId = SecurityUtil.currentUserId();
        return inviteService.generate(request, adminId);
    }

    @PutMapping("/{code}/disable")
    public void disable(@PathVariable String code) {
        inviteService.disable(code);
    }

    @PutMapping("/{code}/enable")
    public void enable(@PathVariable String code) {
        inviteService.enable(code);
    }

    /** 批量操作：禁用 / 启用 / 删除，一次请求处理多选中的邀请码。 */
    @PostMapping("/batch")
    public Map<String, Long> batch(@RequestBody InviteBatchRequest request) {
        return Map.of("affected", inviteService.batch(request.action(), request.codes()));
    }

    @DeleteMapping("/used")
    public Map<String, Long> deleteUsed() {
        return Map.of("deleted", inviteService.deleteUsed());
    }

    @DeleteMapping("/{code}")
    public void delete(@PathVariable String code) {
        inviteService.delete(code);
    }
}
