package com.ykt.platform.service;

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
import com.ykt.platform.entity.PayNotification;
import com.ykt.platform.entity.VipBatchPrice;
import com.ykt.platform.entity.RainBinding;
import com.ykt.platform.entity.User;
import com.ykt.platform.entity.VipAccount;
import com.ykt.platform.entity.VipActivity;
import com.ykt.platform.entity.VipCoupon;
import com.ykt.platform.entity.VipCouponUse;
import com.ykt.platform.entity.VipEntitlement;
import com.ykt.platform.entity.VipOrder;
import com.ykt.platform.entity.VipSettings;
import com.ykt.platform.repository.PayNotificationRepository;
import com.ykt.platform.repository.VipBatchPriceRepository;
import com.ykt.platform.repository.RainBindingRepository;
import com.ykt.platform.repository.UserRepository;
import com.ykt.platform.repository.VipAccountRepository;
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
    private final PayNotificationRepository notificationRepository;
    private final VipBatchPriceRepository batchPriceRepository;
    private final VipSettingsRepository settingsRepository;
    private final AnswerLibService answerLibService;
    private final BackupService backupService;
    private final UserRepository userRepository;
    private final VipAccountRepository vipAccountRepository;
    private final VipService vipService;

    public VipAdminService(VipOrderRepository orderRepository,
                           VipCouponRepository couponRepository,
                           VipCouponUseRepository couponUseRepository,
                           VipEntitlementRepository entitlementRepository,
                           VipActivityRepository activityRepository,
                           RainBindingRepository bindingRepository,
                           PayNotificationRepository notificationRepository,
                           VipBatchPriceRepository batchPriceRepository,
                           VipSettingsRepository settingsRepository,
                           AnswerLibService answerLibService,
                           BackupService backupService,
                           UserRepository userRepository,
                           VipAccountRepository vipAccountRepository,
                           VipService vipService) {
        this.orderRepository = orderRepository;
        this.couponRepository = couponRepository;
        this.couponUseRepository = couponUseRepository;
        this.entitlementRepository = entitlementRepository;
        this.activityRepository = activityRepository;
        this.bindingRepository = bindingRepository;
        this.notificationRepository = notificationRepository;
        this.batchPriceRepository = batchPriceRepository;
        this.settingsRepository = settingsRepository;
        this.answerLibService = answerLibService;
        this.backupService = backupService;
        this.userRepository = userRepository;
        this.vipAccountRepository = vipAccountRepository;
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

    /** 某用户的订单记录（用户详情页「查看充值」）。 */
    @Transactional(readOnly = true)
    public List<VipOrderView> userOrders(Long userId) {
        Map<Long, String> usernames = usernameMap();
        return orderRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
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

    /** 最近到账记录（含未核销，供管理员核对监听漏单）。 */
    @Transactional(readOnly = true)
    public List<VipPayNotificationView> listNotifications() {
        return notificationRepository.findTop50ByOrderByReceivedAtDesc().stream()
                .map(n -> new VipPayNotificationView(n.getId(), n.getChannel(), n.getAmountFen(),
                        n.getRaw(), n.getReceivedAt(), n.isConsumed(), n.getConsumedByOrderNo()))
                .toList();
    }

    /** 删除误报的到账记录（如测试通知）。 */
    @Transactional
    public void deleteNotification(Long id) {
        if (!notificationRepository.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "到账记录不存在");
        }
        notificationRepository.deleteById(id);
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

    // ---------- 导出与维护 ----------

    /** 订单流水 CSV（含 BOM，Excel 直接打开不乱码）。 */
    @Transactional(readOnly = true)
    public String exportOrdersCsv() {
        Map<Long, String> usernames = usernameMap();
        StringBuilder sb = csvHead("订单号,用户,商品,日期批次,原价分,优惠分,尾数分,实付分,状态,渠道,优惠码,用户留言,管理员备注,申报时间,支付时间,创建时间");
        for (VipOrder o : orderRepository.findAllByOrderByCreatedAtDesc()) {
            sb.append(csvRow(
                    o.getOrderNo(), usernames.getOrDefault(o.getUserId(), ""), kindText(o),
                    o.getTargetBatch() == null ? "" : o.getTargetBatch(),
                    o.getOriginalFen(), o.getDiscountFen(), o.getSuffixFen(), o.getPayableFen(),
                    o.getStatus(), channelText(o.getPayChannel()),
                    nvl(o.getCouponCode()), nvl(o.getRemark()), nvl(o.getAdminRemark()),
                    t(o.getClaimedAt()), t(o.getPaidAt()), t(o.getCreatedAt())));
        }
        return sb.toString();
    }

    /** 收款流水 CSV（到账记录池全量）。 */
    @Transactional(readOnly = true)
    public String exportNotificationsCsv() {
        StringBuilder sb = csvHead("时间,渠道,金额分,通知原文,是否核销,核销订单号");
        for (var n : notificationRepository.findTop50ByOrderByReceivedAtDesc()) {
            sb.append(csvRow(
                    t(n.getReceivedAt()), n.getChannel(), n.getAmountFen(),
                    nvl(n.getRaw()), n.isConsumed() ? "已核销" : "待匹配",
                    nvl(n.getConsumedByOrderNo())));
        }
        return sb.toString();
    }

    /** 手动触发过期订单清理（可传 before 截止时间；定时任务每 10 分钟也会自动跑）。 */
    @Transactional
    public long expireStaleOrders(java.time.Instant before) {
        return vipService.expireStaleOrders(before);
    }

    /** 手动触发一次 H2 在线备份，返回备份文件路径。 */
    public String backupNow() throws java.io.IOException {
        return backupService.backupNow();
    }

    private static String kindText(VipOrder o) {
        return VipOrder.KIND_DATE_PASS.equals(o.getKind()) ? "单日解锁" : "月卡";
    }

    private static String channelText(String c) {
        if (c == null) return "";
        return switch (c) {
            case "LISTEN" -> "监听";
            case "MANUAL" -> "人工";
            case "XUNHU" -> "虎皮椒";
            default -> c;
        };
    }

    private static String nvl(String v) {
        return v == null ? "" : v;
    }

    private static String t(java.time.Instant i) {
        return i == null ? "" : java.time.LocalDateTime.ofInstant(i, java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    private static final String CSV_BOM = "\ufeff";
    private static final String CSV_NEWLINE = "\n";

    private static StringBuilder csvHead(String header) {
        return new StringBuilder(CSV_BOM).append(header).append(CSV_NEWLINE);
    }

    private static StringBuilder csvRow(Object... cells) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cells.length; i++) {
            String v = String.valueOf(cells[i]);
            if (v.contains(",") || v.contains("\"") || v.contains("\n")) {
                sb.append('"').append(v.replace("\"", "\"\"")).append('"');
            } else {
                sb.append(v);
            }
            sb.append(i == cells.length - 1 ? CSV_NEWLINE : ',');
        }
        return sb;
    }

    // ---------- 单日按批次定价 ----------

    /** 全部单独定价的日期（未列出的日期用全局默认价）。 */
    @Transactional(readOnly = true)
    public List<VipBatchPriceView> listBatchPrices() {
        return batchPriceRepository.findAllByOrderByBatchAsc().stream()
                .map(b -> new VipBatchPriceView(b.getBatch(), b.getPriceFen(), b.getUpdatedAt()))
                .toList();
    }

    /** 设置某日期的单日价（upsert）；批次必须存在于答案库。 */
    @Transactional
    public VipBatchPriceView setBatchPrice(String batch, int priceFen, Long adminId) {
        if (batch == null || batch.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "batch 不能为空");
        }
        if (priceFen < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "价格需大于 0");
        }
        if (answerLibService.paperNames(batch).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "批次不存在: " + batch);
        }
        VipBatchPrice price = batchPriceRepository.findByBatch(batch).orElseGet(() -> {
            VipBatchPrice created = new VipBatchPrice();
            created.setBatch(batch);
            return created;
        });
        price.setPriceFen(priceFen);
        price.setUpdatedAt(Instant.now());
        price.setUpdatedBy(adminId);
        batchPriceRepository.save(price);
        return new VipBatchPriceView(price.getBatch(), price.getPriceFen(), price.getUpdatedAt());
    }

    /** 删除某日期的单独定价，恢复全局默认价。 */
    @Transactional
    public void deleteBatchPrice(String batch) {
        batchPriceRepository.deleteByBatch(batch);
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

    // ---------- 统计 ----------

    /**
     * VIP 运营统计：收入（累计/今日/近7日）、订单分布、会员与权益、额度、优惠码。
     * 数据量小（本地 H2），直接内存聚合，避免为统计加索引与 native 查询。
     */
    @Transactional(readOnly = true)
    public com.ykt.platform.dto.VipStatsResponse stats() {
        Instant now = Instant.now();
        java.time.LocalDate today = java.time.LocalDate.now();

        List<VipOrder> orders = orderRepository.findAll();
        long totalRevenue = 0;
        long todayRevenue = 0;
        long paid = 0;
        long pending = 0;
        long cancelled = 0;
        long rejected = 0;
        // 近 7 日收入桶（含今日，升序）
        java.time.LocalDate[] days = new java.time.LocalDate[7];
        Map<String, long[]> buckets = new java.util.LinkedHashMap<>(); // date -> [revenueFen, orders]
        for (int i = 6; i >= 0; i--) {
            java.time.LocalDate d = today.minusDays(i);
            days[6 - i] = d;
            buckets.put(d.toString(), new long[2]);
        }
        for (VipOrder o : orders) {
            switch (o.getStatus() == null ? "" : o.getStatus()) {
                case VipOrder.STATUS_PAID -> {
                    paid++;
                    totalRevenue += o.getPayableFen();
                    if (o.getPaidAt() != null) {
                        String d = java.time.LocalDate.ofInstant(o.getPaidAt(), java.time.ZoneId.systemDefault()).toString();
                        long[] bucket = buckets.get(d);
                        if (bucket != null) {
                            bucket[0] += o.getPayableFen();
                            bucket[1] += 1;
                        }
                        String todayKey = today.toString();
                        if (d.equals(todayKey)) {
                            todayRevenue += o.getPayableFen();
                        }
                    }
                }
                case VipOrder.STATUS_CREATED -> {
                    // 过期的待支付单不算「待处理」
                    if (o.getExpiresAt() == null || o.getExpiresAt().isAfter(now)) {
                        pending++;
                    }
                }
                case VipOrder.STATUS_CANCELLED -> cancelled++;
                case VipOrder.STATUS_REJECTED -> rejected++;
                default -> { }
            }
        }
        List<com.ykt.platform.dto.VipStatsResponse.DailyRevenue> daily = new ArrayList<>();
        for (java.time.LocalDate d : days) {
            long[] bucket = buckets.get(d.toString());
            daily.add(new com.ykt.platform.dto.VipStatsResponse.DailyRevenue(
                    d.toString(), bucket == null ? 0 : bucket[0], bucket == null ? 0 : bucket[1]));
        }

        // 会员与权益
        List<VipEntitlement> entitlements = entitlementRepository.findAll();
        long monthActiveUsers = entitlements.stream()
                .filter(e -> !e.isRevoked() && VipEntitlement.KIND_MONTH.equals(e.getKind())
                        && e.getExpiresAt() != null && e.getExpiresAt().isAfter(now))
                .map(VipEntitlement::getUserId)
                .distinct()
                .count();
        long dateEntitlements = entitlements.stream()
                .filter(e -> !e.isRevoked() && VipEntitlement.KIND_DATE.equals(e.getKind()))
                .count();

        // 免费额度总量
        long freeQuotaTotal = 0;
        for (com.ykt.platform.entity.VipAccount acc : vipAccountRepository.findAll()) {
            freeQuotaTotal += Math.max(0, acc.getFreeQuota());
        }

        // 优惠码：可用数 + 累计核销次数
        long couponsActive = couponRepository.findAll().stream()
                .filter(c -> c.usableAt(now))
                .count();
        long couponsUsed = couponRepository.findAll().stream()
                .mapToLong(VipCoupon::usedCountOrDefault)
                .sum();

        // 渠道分布 + 付费用户（转化率口径）
        java.util.Map<String, Long> channelOrders = new java.util.LinkedHashMap<>();
        java.util.Set<Long> paidUserIds = new java.util.HashSet<>();
        for (VipOrder o : orders) {
            if (!VipOrder.STATUS_PAID.equals(o.getStatus())) {
                continue;
            }
            channelOrders.merge(o.getPayChannel() == null ? "UNKNOWN" : o.getPayChannel(), 1L, Long::sum);
            paidUserIds.add(o.getUserId());
        }
        long paidUsers = paidUserIds.size();
        long totalUsers = userRepository.count();

        return new com.ykt.platform.dto.VipStatsResponse(
                totalRevenue, todayRevenue, daily,
                paid, pending, cancelled, rejected,
                monthActiveUsers, dateEntitlements, freeQuotaTotal,
                couponsActive, couponsUsed,
                paidUsers, totalUsers, channelOrders);
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
