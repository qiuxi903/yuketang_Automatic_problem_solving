package com.ykt.platform.service;

import com.ykt.platform.dto.VipBindingView;
import com.ykt.platform.dto.VipCouponCreateRequest;
import com.ykt.platform.dto.VipCouponView;
import com.ykt.platform.dto.VipEntitlementView;
import com.ykt.platform.dto.VipGrantRequest;
import com.ykt.platform.dto.VipOrderView;
import com.ykt.platform.dto.VipSettingsUpdateRequest;
import com.ykt.platform.dto.VipSettingsView;
import com.ykt.platform.entity.RainBinding;
import com.ykt.platform.entity.User;
import com.ykt.platform.entity.VipActivity;
import com.ykt.platform.entity.VipCoupon;
import com.ykt.platform.entity.VipCouponUse;
import com.ykt.platform.entity.VipEntitlement;
import com.ykt.platform.entity.VipOrder;
import com.ykt.platform.entity.VipSettings;
import com.ykt.platform.repository.RainBindingRepository;
import com.ykt.platform.repository.UserRepository;
import com.ykt.platform.repository.VipActivityRepository;
import com.ykt.platform.repository.VipCouponRepository;
import com.ykt.platform.repository.VipCouponUseRepository;
import com.ykt.platform.repository.VipEntitlementRepository;
import com.ykt.platform.repository.VipOrderRepository;
import com.ykt.platform.repository.VipSettingsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** VIP 管理端：订单核销、优惠码、权益发放、绑定解绑、支付设置、活动管理。 */
@Service
public class VipAdminService {

    private static final Logger log = LoggerFactory.getLogger(VipAdminService.class);

    private static final String ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final VipOrderRepository orderRepository;
    private final VipCouponRepository couponRepository;
    private final VipCouponUseRepository couponUseRepository;
    private final VipEntitlementRepository entitlementRepository;
    private final VipActivityRepository activityRepository;
    private final RainBindingRepository bindingRepository;
    private final VipSettingsRepository settingsRepository;
    private final UserRepository userRepository;
    private final VipService vipService;

    public VipAdminService(VipOrderRepository orderRepository,
                           VipCouponRepository couponRepository,
                           VipCouponUseRepository couponUseRepository,
                           VipEntitlementRepository entitlementRepository,
                           VipActivityRepository activityRepository,
                           RainBindingRepository bindingRepository,
                           VipSettingsRepository settingsRepository,
                           UserRepository userRepository,
                           VipService vipService) {
        this.orderRepository = orderRepository;
        this.couponRepository = couponRepository;
        this.couponUseRepository = couponUseRepository;
        this.entitlementRepository = entitlementRepository;
        this.activityRepository = activityRepository;
        this.bindingRepository = bindingRepository;
        this.settingsRepository = settingsRepository;
        this.userRepository = userRepository;
        this.vipService = vipService;
    }

    // ---------- 订单 ----------

    @Transactional(readOnly = true)
    public List<VipOrderView> listOrders(String status) {
        Map<Long, String> usernames = usernameMap();
        return orderRepository.findAllByOrderByCreatedAtDesc().stream()
                .filter(o -> status == null || status.isBlank() || status.equals(o.getStatus()))
                .map(o -> toOrderView(o, usernames))
                .toList();
    }

    /** 管理员确认到账：核销订单并发权益。 */
    @Transactional
    public VipOrderView confirmOrder(String orderNo, String adminRemark, Long adminId) {
        VipOrder order = orderRepository.lockByOrderNo(orderNo)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "订单不存在"));
        if (!VipOrder.STATUS_CREATED.equals(order.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "订单状态为 " + order.getStatus() + "，仅待支付订单可确认");
        }
        order.setAdminRemark(adminRemark);
        vipService.fulfill(order);
        log.info("管理员 {} 人工确认订单 {}（{} 分）", adminId, orderNo, order.getPayableFen());
        return toOrderView(order, Map.of());
    }

    /** 拒单：待支付订单标记 REJECTED（用户侧不可见原因需在 adminRemark 里写明）。 */
    @Transactional
    public VipOrderView rejectOrder(String orderNo, String adminRemark) {
        VipOrder order = orderRepository.lockByOrderNo(orderNo)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "订单不存在"));
        if (!VipOrder.STATUS_CREATED.equals(order.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "仅待支付订单可拒单");
        }
        order.setStatus(VipOrder.STATUS_REJECTED);
        order.setAdminRemark(adminRemark);
        orderRepository.save(order);
        return toOrderView(order, Map.of());
    }

    /** 粘贴收款通知文本快速核销（监听器漏单时的人工兜底）。 */
    @Transactional
    public boolean matchByText(String raw) {
        return vipService.payNotifyText("manual-paste", raw);
    }

    // ---------- 优惠码 ----------

    @Transactional
    public List<String> generateCoupons(VipCouponCreateRequest request, Long adminUserId) {
        String kind = request.kind() == null ? "" : request.kind();
        if (!VipCoupon.KIND_DISCOUNT.equals(kind) && !VipCoupon.KIND_DATE_PASS.equals(kind)
                && !VipCoupon.KIND_MONTH_PASS.equals(kind) && !VipCoupon.KIND_QUOTA.equals(kind)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "优惠码类型无效: " + kind);
        }
        int n = Math.max(1, Math.min(request.count() <= 0 ? 1 : request.count(), 50));
        int maxUses = request.maxUses() == null ? 1 : Math.max(0, Math.min(request.maxUses(), 9999));
        int perUserLimit = request.perUserLimit() == null || request.perUserLimit() < 1 ? 1 : request.perUserLimit();
        Instant expiresAt = request.expiresDays() == null ? null : Instant.now().plusSeconds(request.expiresDays() * 24L * 3600L);
        if (VipCoupon.KIND_DISCOUNT.equals(kind)
                && (request.valuePercent() == null || request.valuePercent() < 1 || request.valuePercent() > 99)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "折扣码需指定 1~99 的折扣百分数（80 = 8 折）");
        }
        List<String> codes = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            VipCoupon coupon = new VipCoupon();
            coupon.setCode("YH-" + randomBlock() + "-" + randomBlock());
            coupon.setKind(kind);
            coupon.setValuePercent(request.valuePercent());
            coupon.setQuotaCount(request.quotaCount());
            coupon.setAppliesTo(request.appliesTo() == null ? "ALL" : request.appliesTo());
            coupon.setMaxUses(maxUses);
            coupon.setPerUserLimit(perUserLimit);
            coupon.setUsedCount(0);
            coupon.setExpiresAt(expiresAt);
            coupon.setCreatedByUserId(adminUserId);
            couponRepository.save(coupon);
            codes.add(coupon.getCode());
        }
        return codes;
    }

    @Transactional(readOnly = true)
    public List<VipCouponView> listCoupons() {
        Map<String, Long> useCounts = new java.util.HashMap<>();
        Map<Long, String> usernames = usernameMap();
        List<VipCoupon> all = couponRepository.findAllByOrderByCreatedAtDesc();
        if (!all.isEmpty()) {
            for (VipCouponUse u : couponUseRepository.findByCodeInOrderByUsedAtDesc(
                    all.stream().map(VipCoupon::getCode).toList())) {
                useCounts.merge(u.getCode(), 1L, Long::sum);
            }
        }
        return all.stream().map(c -> new VipCouponView(
                c.getId(), c.getCode(), c.getKind(), c.getValuePercent(), c.getQuotaCount(),
                c.getAppliesTo(), c.getMaxUses(), c.getPerUserLimit(),
                c.usedCountOrDefault(), c.remainingUses(), c.isDisabled(), c.getExpiresAt(), c.getCreatedAt(),
                usernames.get(c.getCreatedByUserId()))).toList();
    }

    @Transactional
    public void setCouponDisabled(String code, boolean disabled) {
        VipCoupon coupon = couponRepository.findByCode(code)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "优惠码不存在"));
        coupon.setDisabled(disabled);
        couponRepository.save(coupon);
    }

    @Transactional
    public long couponBatch(String action, List<String> codes) {
        if (codes == null || codes.isEmpty()) return 0;
        List<VipCoupon> found = couponRepository.findByCodeIn(codes);
        switch (action == null ? "" : action) {
            case "disable" -> found.forEach(c -> c.setDisabled(true));
            case "enable" -> found.forEach(c -> c.setDisabled(false));
            case "delete" -> {
                couponUseRepository.deleteByCodeIn(found.stream().map(VipCoupon::getCode).toList());
                couponRepository.deleteAll(found);
                return found.size();
            }
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "不支持的操作：" + action + "（可用 disable / enable / delete）");
        }
        couponRepository.saveAll(found);
        return found.size();
    }

    // ---------- 权益与额度 ----------

    @Transactional(readOnly = true)
    public List<VipEntitlementView> listEntitlements() {
        Map<Long, String> usernames = usernameMap();
        return entitlementRepository.findAll().stream()
                .map(e -> new VipEntitlementView(e.getId(), e.getUserId(), usernames.get(e.getUserId()),
                        e.getKind(), e.getTargetBatch(), e.getExpiresAt(), e.isRevoked(),
                        e.getSourceOrderNo(), e.getCreatedAt()))
                .sorted((a, b) -> b.createdAt().compareTo(a.createdAt()))
                .toList();
    }

    @Transactional
    public String grant(Long userId, VipGrantRequest request, Long adminId) {
        String kind = request.kind() == null ? "" : request.kind();
        switch (kind) {
            case "MONTH" -> {
                int days = request.days() == null ? vipService.settings().getMonthDays() : Math.max(1, request.days());
                Instant expiresAt = vipService.grantMonth(userId, days, null, adminId);
                return "月卡已发放，到期 " + expiresAt;
            }
            case "DATE" -> {
                if (request.targetBatch() == null || request.targetBatch().isBlank()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "发放单日权益需指定批次");
                }
                vipService.grantDatePass(userId, request.targetBatch(), null, adminId);
                return "日期 " + request.targetBatch() + " 已解锁";
            }
            case "QUOTA" -> {
                int quota = request.quota() == null ? 1 : request.quota();
                var acc = vipService.account(userId);
                acc.setFreeQuota(Math.max(0, acc.getFreeQuota() + quota));
                acc.setUpdatedAt(Instant.now());
                return "额度 " + (quota >= 0 ? "+" : "") + quota + "，当前 " + acc.getFreeQuota() + " 次";
            }
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "权益类型无效: " + kind + "（MONTH / DATE / QUOTA）");
        }
    }

    @Transactional
    public void revokeEntitlement(Long id) {
        VipEntitlement e = entitlementRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "权益不存在"));
        e.setRevoked(true);
        entitlementRepository.save(e);
    }

    // ---------- 绑定 ----------

    @Transactional(readOnly = true)
    public List<VipBindingView> listBindings() {
        Map<Long, String> usernames = usernameMap();
        return bindingRepository.findAllByOrderByBoundAtDesc().stream()
                .map(b -> new VipBindingView(b.getId(), b.getUserId(), usernames.get(b.getUserId()),
                        b.getYktName(), b.isAutoBound(), b.getBoundAt()))
                .toList();
    }

    /** 解绑：删除绑定行，用户下次扫码重新绑定；同雨课堂账号释放给其他平台账号。 */
    @Transactional
    public void unbind(Long userId) {
        RainBinding binding = bindingRepository.findByUserId(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "该用户没有绑定记录"));
        bindingRepository.delete(binding);
        log.info("管理员已解绑用户 {} 的雨课堂账号「{}」", userId, binding.getYktName());
    }

    // ---------- 活动与设置 ----------

    @Transactional(readOnly = true)
    public List<VipActivity> listActivities() {
        return activityRepository.findAllByOrderByCreatedAtDesc();
    }

    @Transactional
    public VipActivity saveActivity(VipActivity activity) {
        if (activity.getName() == null || activity.getName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "活动名称不能为空");
        }
        if (activity.getDiscountPercent() < 1 || activity.getDiscountPercent() > 99) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "折扣百分数需在 1~99（80 = 8 折）");
        }
        return activityRepository.save(activity);
    }

    @Transactional
    public void deleteActivity(Long id) {
        activityRepository.deleteById(id);
    }

    @Transactional(readOnly = true)
    public VipSettingsView settingsView() {
        VipSettings s = vipService.settings();
        return new VipSettingsView(s.getDatePassPriceFen(), s.getMonthPriceFen(), s.getMonthDays(),
                s.getPayChannel(),
                s.getPayQrWechat() != null && !s.getPayQrWechat().isBlank(),
                s.getPayQrAlipay() != null && !s.getPayQrAlipay().isBlank(),
                s.getPayNote(), s.getListenToken());
    }

    @Transactional
    public VipSettingsView updateSettings(VipSettingsUpdateRequest request) {
        VipSettings s = vipService.settings();
        if (request.datePassPriceFen() != null) {
            if (request.datePassPriceFen() < 1) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "价格需大于 0");
            s.setDatePassPriceFen(request.datePassPriceFen());
        }
        if (request.monthPriceFen() != null) {
            if (request.monthPriceFen() < 1) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "价格需大于 0");
            s.setMonthPriceFen(request.monthPriceFen());
        }
        if (request.monthDays() != null) {
            s.setMonthDays(Math.max(1, request.monthDays()));
        }
        if (request.payChannel() != null && !request.payChannel().isBlank()) {
            String channel = request.payChannel();
            if (!VipOrder.CHANNEL_LISTEN.equals(channel) && !VipOrder.CHANNEL_MANUAL.equals(channel)
                    && !VipOrder.CHANNEL_XUNHU.equals(channel)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "收款渠道无效: " + channel);
            }
            s.setPayChannel(channel);
        }
        if (request.payQrWechat() != null) {
            s.setPayQrWechat(request.payQrWechat().isBlank() ? null : request.payQrWechat());
        }
        if (request.payQrAlipay() != null) {
            s.setPayQrAlipay(request.payQrAlipay().isBlank() ? null : request.payQrAlipay());
        }
        if (request.payNote() != null) {
            s.setPayNote(request.payNote().isBlank() ? null : request.payNote());
        }
        s.setUpdatedAt(Instant.now());
        settingsRepository.save(s);
        return settingsView();
    }

    /** 暴露给 PayNotifyController 的令牌校验。 */
    @Transactional(readOnly = true)
    public boolean listenTokenValid(String token) {
        VipSettings s = vipService.settings();
        return s.getListenToken() != null && s.getListenToken().equals(token);
    }

    // ---------- 私有工具 ----------

    private Map<Long, String> usernameMap() {
        return userRepository.findAll().stream()
                .collect(Collectors.toMap(User::getId, User::getUsername, (a, b) -> a));
    }

    private VipOrderView toOrderView(VipOrder o, Map<Long, String> usernames) {
        return new VipOrderView(
                o.getId(), o.getOrderNo(), o.getUserId(), usernames.get(o.getUserId()),
                o.getKind(), o.getTargetBatch(),
                o.getOriginalFen(), o.getDiscountFen(), o.getSuffixFen(), o.getPayableFen(),
                o.getStatus(), o.getPayChannel(), o.getCouponCode(),
                o.getRemark(), o.getAdminRemark(),
                o.getClaimedAt(), o.getPaidAt(), o.getExpiresAt(), o.getCreatedAt(),
                null);
    }

    private static String randomBlock() {
        StringBuilder sb = new StringBuilder(4);
        for (int i = 0; i < 4; i++) {
            sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}
