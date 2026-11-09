package com.ykt.platform.controller;

import com.ykt.platform.dto.VipBatchPriceSetRequest;
import com.ykt.platform.dto.VipBatchPriceView;
import com.ykt.platform.dto.VipBindingView;
import com.ykt.platform.dto.VipCouponCreateRequest;
import com.ykt.platform.dto.VipCouponView;
import com.ykt.platform.dto.VipEntitlementView;
import com.ykt.platform.dto.VipGrantRequest;
import com.ykt.platform.dto.VipOrderView;
import com.ykt.platform.dto.VipPayNotificationView;
import com.ykt.platform.dto.VipSettingsUpdateRequest;
import com.ykt.platform.dto.VipSettingsView;
import com.ykt.platform.dto.VipStatsResponse;
import com.ykt.platform.entity.VipActivity;
import com.ykt.platform.security.SecurityUtil;
import com.ykt.platform.service.VipAdminService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** VIP 运营管理（仅管理员）。 */
@RestController
@RequestMapping("/api/admin/vip")
public class AdminVipController {

    private final VipAdminService adminService;

    public AdminVipController(VipAdminService adminService) {
        this.adminService = adminService;
    }

    // ---------- 订单 ----------

    /** VIP 运营统计：收入（累计/今日/近7日）、订单分布、会员与权益、额度、优惠码。 */
    @GetMapping("/stats")
    public VipStatsResponse stats() {
        return adminService.stats();
    }

    @GetMapping("/orders")
    public List<VipOrderView> orders(@RequestParam(required = false) String status) {
        return adminService.listOrders(status);
    }

    @PostMapping("/orders/{orderNo}/confirm")
    public VipOrderView confirmOrder(@PathVariable String orderNo,
                                     @RequestBody(required = false) Map<String, String> body) {
        return adminService.confirmOrder(orderNo,
                body == null ? null : body.get("adminRemark"), SecurityUtil.currentUserId());
    }

    @PostMapping("/orders/{orderNo}/reject")
    public VipOrderView rejectOrder(@PathVariable String orderNo,
                                    @RequestBody(required = false) Map<String, String> body) {
        return adminService.rejectOrder(orderNo, body == null ? null : body.get("adminRemark"));
    }

    /** 粘贴收款通知文本快速核销（监听漏单兜底）。 */
    @PostMapping("/orders/match-text")
    public Map<String, Object> matchText(@RequestBody Map<String, String> body) {
        boolean matched = adminService.matchByText(body.getOrDefault("raw", ""));
        return Map.of("matched", matched);
    }

    /** 最近到账记录（含未核销）。 */
    @GetMapping("/pay-notifications")
    public List<VipPayNotificationView> notifications() {
        return adminService.listNotifications();
    }

    /** 删除误报的到账记录。 */
    @DeleteMapping("/pay-notifications/{id}")
    public void deleteNotification(@PathVariable Long id) {
        adminService.deleteNotification(id);
    }

    // ---------- 优惠码 ----------

    @GetMapping("/coupons")
    public List<VipCouponView> coupons() {
        return adminService.listCoupons();
    }

    @PostMapping("/coupons")
    public Map<String, Object> generateCoupons(@RequestBody VipCouponCreateRequest request) {
        return Map.of("codes", adminService.generateCoupons(request, SecurityUtil.currentUserId()));
    }

    @PutMapping("/coupons/{code}/disable")
    public void disableCoupon(@PathVariable String code) {
        adminService.setCouponDisabled(code, true);
    }

    @PutMapping("/coupons/{code}/enable")
    public void enableCoupon(@PathVariable String code) {
        adminService.setCouponDisabled(code, false);
    }

    @PostMapping("/coupons/batch")
    public Map<String, Long> couponBatch(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<String> codes = (List<String>) body.get("codes");
        return Map.of("affected", adminService.couponBatch((String) body.get("action"), codes));
    }

    // ---------- 权益与绑定 ----------

    @GetMapping("/entitlements")
    public List<VipEntitlementView> entitlements() {
        return adminService.listEntitlements();
    }

    @PostMapping("/users/{userId}/grant")
    public Map<String, Object> grant(@PathVariable Long userId, @RequestBody VipGrantRequest request) {
        String message = adminService.grant(userId, request, SecurityUtil.currentUserId());
        return Map.of("ok", true, "message", message);
    }

    @DeleteMapping("/entitlements/{id}")
    public void revokeEntitlement(@PathVariable Long id) {
        adminService.revokeEntitlement(id);
    }

    @GetMapping("/bindings")
    public List<VipBindingView> bindings() {
        return adminService.listBindings();
    }

    @DeleteMapping("/users/{userId}/binding")
    public void unbind(@PathVariable Long userId) {
        adminService.unbind(userId);
    }

    // ---------- 单日按批次定价 ----------

    @GetMapping("/batch-prices")
    public List<VipBatchPriceView> batchPrices() {
        return adminService.listBatchPrices();
    }

    @PutMapping("/batch-prices")
    public VipBatchPriceView setBatchPrice(@RequestBody VipBatchPriceSetRequest request) {
        return adminService.setBatchPrice(request.batch(), request.priceFen(), SecurityUtil.currentUserId());
    }

    @DeleteMapping("/batch-prices/{batch}")
    public void deleteBatchPrice(@PathVariable String batch) {
        adminService.deleteBatchPrice(batch);
    }

    // ---------- 活动与设置 ----------

    @GetMapping("/activities")
    public List<VipActivity> activities() {
        return adminService.listActivities();
    }

    @PostMapping("/activities")
    public VipActivity saveActivity(@RequestBody VipActivity activity) {
        return adminService.saveActivity(activity);
    }

    @DeleteMapping("/activities/{id}")
    public void deleteActivity(@PathVariable Long id) {
        adminService.deleteActivity(id);
    }

    @GetMapping("/settings")
    public VipSettingsView settings() {
        return adminService.settingsView();
    }

    @PutMapping("/settings")
    public VipSettingsView updateSettings(@RequestBody VipSettingsUpdateRequest request) {
        return adminService.updateSettings(request);
    }
}
