package com.ykt.platform.service;

import com.ykt.platform.config.VipProperties;
import com.ykt.platform.dto.PayNotifyRequest;
import com.ykt.platform.dto.VipClaimResultResponse;
import com.ykt.platform.dto.PaperManifestResponse;
import com.ykt.platform.dto.VipActivityView;
import com.ykt.platform.dto.VipOrderCreateRequest;
import com.ykt.platform.dto.VipOrderView;
import com.ykt.platform.dto.VipProductsResponse;
import com.ykt.platform.dto.VipQuotaUnlockRequest;
import com.ykt.platform.dto.VipRedeemRequest;
import com.ykt.platform.dto.VipStatusResponse;
import com.ykt.platform.entity.PayNotification;
import com.ykt.platform.entity.VipBatchPrice;
import com.ykt.platform.entity.RainBinding;
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
import com.ykt.platform.vip.VipRequiredException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * VIP 用户侧核心：权益判定（闸门）、订单、兑换码、免费额度、雨课堂绑定、监听核销。
 * 金额一律用分；权益判定以 VipEntitlement 为唯一事实来源。
 */
@Service
public class VipService {

    private static final Logger log = LoggerFactory.getLogger(VipService.class);

    private static final String ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";
    private static final SecureRandom RANDOM = new SecureRandom();

    /** 从收款通知文本里提取金额（「收款3.47元」/「¥3.47」/「到账 12.00 元」）。 */
    private static final Pattern AMOUNT_PATTERN =
            Pattern.compile("(?:¥|￥)?\\s*([0-9]+(?:\\.[0-9]{1,2})?)\\s*元?");

    /** 监听核销的候选窗口：只匹配最近这么久创建的待支付订单。 */
    private static final long MATCH_WINDOW_SECONDS = 600;

    private final VipSettingsRepository settingsRepository;
    private final VipOrderRepository orderRepository;
    private final VipEntitlementRepository entitlementRepository;
    private final VipCouponRepository couponRepository;
    private final VipCouponUseRepository couponUseRepository;
    private final VipAccountRepository accountRepository;
    private final VipActivityRepository activityRepository;
    private final RainBindingRepository bindingRepository;
    private final PayNotificationRepository notificationRepository;
    private final VipBatchPriceRepository batchPriceRepository;
    private final UserRepository userRepository;
    private final AnswerLibService answerLibService;
    private final VipProperties properties;

    public VipService(VipSettingsRepository settingsRepository,
                      VipOrderRepository orderRepository,
                      VipEntitlementRepository entitlementRepository,
                      VipCouponRepository couponRepository,
                      VipCouponUseRepository couponUseRepository,
                      VipAccountRepository accountRepository,
                      VipActivityRepository activityRepository,
                      RainBindingRepository bindingRepository,
                      PayNotificationRepository notificationRepository,
                      VipBatchPriceRepository batchPriceRepository,
                      UserRepository userRepository,
                      AnswerLibService answerLibService,
                      VipProperties properties) {
        this.settingsRepository = settingsRepository;
        this.orderRepository = orderRepository;
        this.entitlementRepository = entitlementRepository;
        this.couponRepository = couponRepository;
        this.couponUseRepository = couponUseRepository;
        this.accountRepository = accountRepository;
        this.activityRepository = activityRepository;
        this.bindingRepository = bindingRepository;
        this.notificationRepository = notificationRepository;
        this.batchPriceRepository = batchPriceRepository;
        this.userRepository = userRepository;
        this.answerLibService = answerLibService;
        this.properties = properties;
    }

    // ---------- 设置 ----------

    @Transactional
    public VipSettings settings() {
        return settingsRepository.findAll().stream().findFirst().orElseGet(() -> {
            VipSettings s = new VipSettings();
            s.setListenToken(randomToken(32));
            log.info("VIP 设置缺失，已按默认值种子（单日 300 分 / 月卡 2900 分 / 30 天 / LISTEN）");
            return settingsRepository.save(s);
        });
    }

    // ---------- 用户权益 ----------

    /** 用户 VIP 账户（免费额度计数），懒创建。 */
    @Transactional
    public VipAccount account(Long userId) {
        return accountRepository.findByUserId(userId).orElseGet(() -> {
            VipAccount a = new VipAccount();
            a.setUserId(userId);
            a.setFreeQuota(properties.freeQuotaOnRegister());
            return accountRepository.save(a);
        });
    }

    /**
     * 某日期的单日解锁价：管理员单独定价优先，否则用全局默认价。
     */
    @Transactional(readOnly = true)
    public int datePassPriceFor(String batch) {
        VipSettings settings = settings();
        return batchPriceRepository.findByBatch(batch)
                .map(VipBatchPrice::getPriceFen)
                .orElse(settings.getDatePassPriceFen());
    }

    @Transactional(readOnly = true)
    public boolean monthCardActive(Long userId) {
        return entitlementRepository
                .findFirstByUserIdAndKindAndRevokedFalseAndExpiresAtAfterOrderByExpiresAtDesc(
                        userId, VipEntitlement.KIND_MONTH, Instant.now())
                .isPresent();
    }

    @Transactional(readOnly = true)
    public Instant monthCardExpiresAt(Long userId) {
        return entitlementRepository
                .findFirstByUserIdAndKindAndRevokedFalseAndExpiresAtAfterOrderByExpiresAtDesc(
                        userId, VipEntitlement.KIND_MONTH, Instant.now())
                .map(VipEntitlement::getExpiresAt)
                .orElse(null);
    }

    @Transactional(readOnly = true)
    public boolean batchUnlocked(Long userId, String batch) {
        return monthCardActive(userId)
                || entitlementRepository
                        .findFirstByUserIdAndKindAndTargetBatchAndRevokedFalseOrderByIdAsc(
                                userId, VipEntitlement.KIND_DATE, batch)
                        .isPresent();
    }

    @Transactional(readOnly = true)
    public List<String> unlockedBatches(Long userId) {
        List<String> result = new ArrayList<>();
        for (VipEntitlement e : entitlementRepository.findByUserIdAndRevokedFalse(userId)) {
            if (VipEntitlement.KIND_DATE.equals(e.getKind()) && e.getTargetBatch() != null) {
                result.add(e.getTargetBatch());
            }
        }
        return result;
    }

    /**
     * 付费闸门：enforce 关闭或管理员直接放行；月卡在期或持有该批次单日权益放行；
     * 否则抛 {@link VipRequiredException}（→ 402，前端跳付费页）。
     *
     * 收费边界：只有「自动做题」（mode=answer，含缺省）收费；
     * 「取答案」（mode=collect）与答案中心的浏览/下载永远免费。
     */
    @Transactional(readOnly = true)
    public void checkBatchAccess(Long userId, String batch, boolean admin, String mode) {
        if (!properties.enforce()) {
            return;
        }
        if (admin || batch == null || batch.isBlank()) {
            return;
        }
        // collect=只采答案不自动作答：免费放行
        if ("collect".equalsIgnoreCase(mode)) {
            return;
        }
        if (batchUnlocked(userId, batch)) {
            return;
        }
        throw new VipRequiredException(batch);
    }

    @Transactional(readOnly = true)
    public VipStatusResponse status(Long userId) {
        VipSettings settings = settings();
        RainBinding binding = bindingRepository.findByUserId(userId).orElse(null);
        Instant monthExpires = monthCardExpiresAt(userId);
        return new VipStatusResponse(
                properties.enforce(),
                monthExpires,
                monthExpires != null && monthExpires.isAfter(Instant.now()),
                account(userId).getFreeQuota(),
                unlockedBatches(userId),
                binding == null ? null : binding.getYktName(),
                settings.getPayChannel(),
                settings.getPayNote());
    }

    @Transactional(readOnly = true)
    public VipProductsResponse products(Long userId) {
        VipSettings settings = settings();
        PaperManifestResponse manifest = answerLibService.getManifest();
        List<VipProductsResponse.VipBatchView> batches = manifest.batches().stream()
                .map(b -> new VipProductsResponse.VipBatchView(
                        b.batch(), b.papersCount(), !properties.enforce() || batchUnlocked(userId, b.batch()),
                        datePassPriceFor(b.batch())))
                .toList();
        return new VipProductsResponse(
                settings.getDatePassPriceFen(),
                settings.getMonthPriceFen(),
                settings.getMonthDays(),
                settings.getPayChannel(),
                settings.getPayNote(),
                settings.getPayQrWechat() != null && !settings.getPayQrWechat().isBlank(),
                settings.getPayQrAlipay() != null && !settings.getPayQrAlipay().isBlank(),
                effectiveActivity(Instant.now(), "ALL"),
                batches);
    }

    /** 收款码图片（base64 dataURL），付费页支付弹层展示用。 */
    @Transactional(readOnly = true)
    public Map<String, String> payQrImages() {
        VipSettings settings = settings();
        Map<String, String> out = new LinkedHashMap<>();
        out.put("wechat", blankToNull(settings.getPayQrWechat()));
        out.put("alipay", blankToNull(settings.getPayQrAlipay()));
        return out;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    // ---------- 订单 ----------

    @Transactional
    public VipOrderView createOrder(Long userId, VipOrderCreateRequest request) {
        VipSettings settings = settings();
        String kind = request.kind() == null ? "" : request.kind();
        if (!VipOrder.KIND_DATE_PASS.equals(kind) && !VipOrder.KIND_MONTH_CARD.equals(kind)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "商品类型无效: " + kind);
        }
        String targetBatch = request.targetBatch() == null || request.targetBatch().isBlank()
                ? null : request.targetBatch();
        if (VipOrder.KIND_DATE_PASS.equals(kind)) {
            if (targetBatch == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择要解锁的日期批次");
            }
            if (answerLibService.paperNames(targetBatch).isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "批次不存在: " + targetBatch);
            }
            if (batchUnlocked(userId, targetBatch)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "该日期已解锁，无需重复购买");
            }
        }

        int originalFen = VipOrder.KIND_DATE_PASS.equals(kind)
                ? datePassPriceFor(targetBatch) : settings.getMonthPriceFen();

        // 折扣：活动自动生效 + 用户填的折扣码，二者取最优（不叠加）
        Instant now = Instant.now();
        int percent = 100;
        VipActivityView bestActivity = effectiveActivity(now, VipOrder.KIND_DATE_PASS.equals(kind) ? "DATE" : "MONTH");
        String appliedCoupon = null;
        if (bestActivity != null) {
            percent = Math.min(percent, bestActivity.discountPercent());
        }
        if (request.couponCode() != null && !request.couponCode().isBlank()) {
            String code = request.couponCode().trim();
            VipCoupon coupon = couponRepository.lockByCode(code)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "优惠码无效: " + code));
            if (!VipCoupon.KIND_DISCOUNT.equals(coupon.getKind())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "该码是兑换码，请到「兑换中心」直接兑换，不要在下单时填写");
            }
            requireCouponUsable(coupon, userId, now);
            String appliesTo = coupon.getAppliesTo() == null ? "ALL" : coupon.getAppliesTo();
            if (!"ALL".equalsIgnoreCase(appliesTo)
                    && !appliesTo.equalsIgnoreCase(VipOrder.KIND_DATE_PASS.equals(kind) ? "DATE" : "MONTH")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "该优惠码不适用于当前商品");
            }
            if (coupon.getValuePercent() != null && coupon.getValuePercent() < percent) {
                percent = coupon.getValuePercent();
                appliedCoupon = coupon.getCode();
            }
        }

        int discountFen = Math.round(originalFen * (100 - percent) / 100.0f);
        // 免签监听靠「精确金额」对单：加 1~99 分随机尾数把订单区分开；人工/虎皮椒不加
        String channel = settings.getPayChannel();
        if (VipOrder.CHANNEL_XUNHU.equals(channel) && (properties.xunhuAppid() == null || properties.xunhuAppid().isBlank())) {
            log.warn("虎皮椒渠道未配置 appid，订单回落人工确认渠道");
            channel = VipOrder.CHANNEL_MANUAL;
        }
        int suffixFen = VipOrder.CHANNEL_LISTEN.equals(channel) ? 1 + RANDOM.nextInt(99) : 0;

        VipOrder order = new VipOrder();
        order.setOrderNo("VIP" + Long.toString(System.currentTimeMillis(), 36).toUpperCase() + randomToken(4));
        order.setUserId(userId);
        order.setKind(kind);
        order.setTargetBatch(targetBatch);
        order.setOriginalFen(originalFen);
        order.setDiscountFen(discountFen);
        order.setSuffixFen(suffixFen);
        order.setPayableFen(originalFen - discountFen + suffixFen);
        order.setStatus(VipOrder.STATUS_CREATED);
        order.setPayChannel(channel);
        order.setCouponCode(appliedCoupon);
        order.setRemark(request.remark());
        order.setExpiresAt(now.plusSeconds(MATCH_WINDOW_SECONDS));
        orderRepository.save(order);
        return toOrderView(order, null, bestActivity == null ? null : bestActivity.name());
    }

    @Transactional(readOnly = true)
    public List<VipOrderView> myOrders(Long userId) {
        return orderRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(o -> toOrderView(o, null, null))
                .toList();
    }

    /**
     * 用户点「我已支付，核实到账」：按订单实付金额精确比对到账记录池中未核销的收款，
     * 符合 → 核销该记录并立即开通（verified=true）；不符合 → 记录申报时间、保持待支付，
     * 监听回调与管理员确认仍会在后续自动/人工开通（verified=false，message 给用户提示）。
     */
    @Transactional
    public VipClaimResultResponse claimPaid(Long userId, String orderNo) {
        VipOrder order = ownOrder(userId, orderNo);
        if (VipOrder.STATUS_PAID.equals(order.getStatus())) {
            return new VipClaimResultResponse(true, "该订单此前已核实到账并开通");
        }
        if (!VipOrder.STATUS_CREATED.equals(order.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "订单当前状态不可申报付款");
        }
        Instant now = Instant.now();
        // 收款不可能早于下单太多：窗口 = 下单前 2 分钟起（留时钟偏差）
        List<PayNotification> candidates = notificationRepository
                .findByConsumedFalseAndAmountFenAndReceivedAtAfterOrderByReceivedAtAsc(
                        order.getPayableFen(), order.getCreatedAt().minusSeconds(120));
        PayNotification hit = candidates.stream()
                .filter(n -> !n.getReceivedAt().isAfter(now.plusSeconds(300)))
                .findFirst()
                .orElse(null);
        if (hit != null) {
            fulfill(order);
            consumeNotification(hit, order.getOrderNo());
            log.info("用户申报核实通过：订单 {} 与到账记录 #{}（{} 分）匹配，自动开通",
                    order.getOrderNo(), hit.getId(), hit.getAmountFen());
            return new VipClaimResultResponse(true,
                    String.format("已核实到账 ¥%.2f，权益已开通", hit.getAmountFen() / 100.0));
        }
        order.setClaimedAt(now);
        orderRepository.save(order);
        return new VipClaimResultResponse(false,
                String.format("暂未检测到 ¥%.2f 的到账记录：请确认已按显示金额精确支付；到账后本页会自动检测并开通（无需重复下单）",
                        order.getPayableFen() / 100.0));
    }

    /** 到账记录入池（先存后匹配，没匹配上也不丢，供后续申报核实）。 */
    @Transactional
    public PayNotification recordNotification(String channel, int amountFen, String raw) {
        PayNotification n = new PayNotification();
        n.setChannel(channel == null || channel.isBlank() ? "unknown" : channel);
        n.setAmountFen(amountFen);
        n.setRaw(raw);
        n.setReceivedAt(Instant.now());
        return notificationRepository.save(n);
    }

    /** 核销到账记录（自动匹配或申报核实都回填订单号，便于对账）。 */
    @Transactional
    public void consumeNotification(PayNotification notification, String orderNo) {
        notification.setConsumed(true);
        notification.setConsumedByOrderNo(orderNo);
        notificationRepository.save(notification);
    }

    @Transactional
    public void cancel(Long userId, String orderNo) {
        VipOrder order = ownOrder(userId, orderNo);
        if (!VipOrder.STATUS_CREATED.equals(order.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "仅待支付订单可取消");
        }
        order.setStatus(VipOrder.STATUS_CANCELLED);
        orderRepository.save(order);
    }

    /**
     * 免签监听回调：按实付金额精确匹配窗口内的待支付订单并自动核销。
     * 撞单（同金额多单）取创建最早的，其余留在待支付由人工兜底；完全无匹配时记日志供人工核对。
     */
    /** 按金额精确匹配窗口内待支付订单并核销；返回匹配到的订单（null = 未匹配，记录留池待申报核实）。 */
    @Transactional
    public VipOrder payNotify(String channel, int amountFen, String raw) {
        Instant now = Instant.now();
        List<VipOrder> candidates = orderRepository
                .findByStatusAndPayableFenAndCreatedAtAfterOrderByCreatedAtAsc(
                        VipOrder.STATUS_CREATED, amountFen, now.minusSeconds(MATCH_WINDOW_SECONDS))
                .stream()
                .filter(o -> o.getExpiresAt().isAfter(now))
                .toList();
        if (candidates.isEmpty()) {
            log.warn("到账记录未匹配到订单（留池待申报核实）：channel={} amountFen={} raw={}", channel, amountFen, raw);
            return null;
        }
        VipOrder order = candidates.get(0);
        if (candidates.size() > 1) {
            log.warn("金额 {} 分匹配到 {} 个订单，按最早创建核销 {}（其余请人工核对）",
                    amountFen, candidates.size(), order.getOrderNo());
        }
        fulfill(order);
        log.info("到账自动核销订单 {}（{} 分，channel={}）", order.getOrderNo(), amountFen, channel);
        return order;
    }

    /** 从收款通知文本提取金额 → 入池 → 匹配订单（管理端快速核销入口）。 */
    @Transactional
    public boolean payNotifyText(String channel, String raw) {
        Integer amountFen = extractAmountFen(raw);
        if (amountFen == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "未能从文本中识别收款金额");
        }
        PayNotification saved = recordNotification(channel, amountFen, raw);
        VipOrder order = payNotify(channel, amountFen, raw);
        if (order != null) {
            consumeNotification(saved, order.getOrderNo());
            return true;
        }
        return false;
    }

    /** 订单核销并发放权益（PAID 事实上只允许走这一处，保证幂等）。 */
    @Transactional
    public void fulfill(VipOrder order) {
        if (VipOrder.STATUS_PAID.equals(order.getStatus())) {
            return;
        }
        order.setStatus(VipOrder.STATUS_PAID);
        order.setPaidAt(Instant.now());
        orderRepository.save(order);
        if (VipOrder.KIND_DATE_PASS.equals(order.getKind())) {
            grantDatePass(order.getUserId(), order.getTargetBatch(), order.getOrderNo(), null);
        } else {
            grantMonth(order.getUserId(), settings().getMonthDays(), order.getOrderNo(), null);
        }
        // 折扣码随订单核销才计数：下单后取消/拒单不浪费优惠码
        if (order.getCouponCode() != null && !order.getCouponCode().isBlank()) {
            couponRepository.findByCode(order.getCouponCode()).ifPresent(coupon -> {
                coupon.setUsedCount(coupon.usedCountOrDefault() + 1);
                couponRepository.save(coupon);
                VipCouponUse use = new VipCouponUse();
                use.setCode(coupon.getCode());
                use.setUserId(order.getUserId());
                use.setOrderNo(order.getOrderNo());
                couponUseRepository.save(use);
            });
        }
    }

    // ---------- 兑换码 / 免费额度 ----------

    @Transactional
    public String redeem(Long userId, VipRedeemRequest request) {
        String code = request.code() == null ? "" : request.code().trim();
        if (code.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请输入兑换码");
        }
        Instant now = Instant.now();
        VipCoupon coupon = couponRepository.lockByCode(code)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "兑换码无效"));
        requireCouponUsable(coupon, userId, now);
        switch (coupon.getKind()) {
            case VipCoupon.KIND_DATE_PASS -> {
                String batch = request.targetBatch();
                if (batch == null || batch.isBlank()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "单日兑换码请先选择要解锁的日期");
                }
                if (answerLibService.paperNames(batch).isEmpty()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "批次不存在: " + batch);
                }
                if (batchUnlocked(userId, batch)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "该日期已解锁，无需兑换");
                }
                consumeCoupon(coupon, userId, null);
                grantDatePass(userId, batch, null, null);
                return "兑换成功：日期 " + batch + " 已解锁";
            }
            case VipCoupon.KIND_MONTH_PASS -> {
                consumeCoupon(coupon, userId, null);
                grantMonth(userId, settings().getMonthDays(), null, null);
                return "兑换成功：月卡已生效，到期时间 " + monthCardExpiresAt(userId);
            }
            case VipCoupon.KIND_QUOTA -> {
                consumeCoupon(coupon, userId, null);
                VipAccount acc = account(userId);
                acc.setFreeQuota(acc.getFreeQuota() + (coupon.getQuotaCount() == null ? 1 : coupon.getQuotaCount()));
                acc.setUpdatedAt(now);
                accountRepository.save(acc);
                return "兑换成功：免费额度 +"
                        + (coupon.getQuotaCount() == null ? 1 : coupon.getQuotaCount())
                        + "（当前 " + acc.getFreeQuota() + " 次）";
            }
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "折扣码请在下单时填写，不能直接兑换");
        }
    }

    /** 消耗 1 次免费额度解锁指定批次（前端弹窗确认后调用）。 */
    @Transactional
    public void unlockWithQuota(Long userId, VipQuotaUnlockRequest request) {
        String batch = request.targetBatch();
        if (batch == null || batch.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "targetBatch 不能为空");
        }
        if (answerLibService.paperNames(batch).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "批次不存在: " + batch);
        }
        if (batchUnlocked(userId, batch)) {
            return;
        }
        VipAccount acc = account(userId);
        if (acc.getFreeQuota() <= 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "免费额度不足，请购买或使用兑换码");
        }
        acc.setFreeQuota(acc.getFreeQuota() - 1);
        acc.setUpdatedAt(Instant.now());
        accountRepository.save(acc);
        grantDatePass(userId, batch, null, null);
    }

    // ---------- 雨课堂账号绑定 ----------

    /**
     * 扫码登录后的绑定校验：
     * 已绑定且账号一致 → 放行；已绑定但不一致 → 拒绝理由；未绑定 → 自动 1:1 绑定（该雨课堂账号未被他人占用时）。
     *
     * @return null = 通过；非 null = 拒绝理由（调用方据此把会话置 REJECTED）
     */
    @Transactional
    public String onScanLogin(Long userId, String yktName) {
        if (!properties.bindRequired()) {
            return null;
        }
        if (yktName == null || yktName.isBlank()) {
            // 读不到账号名不在此处拒绝：worker 侧（VIP 分支）必须读到名字才上报 SCANNED
            return null;
        }
        RainBinding mine = bindingRepository.findByUserId(userId).orElse(null);
        if (mine != null) {
            if (mine.getYktName().equals(yktName)) {
                return null;
            }
            return "该平台账号已绑定雨课堂账号「" + mine.getYktName() + "」，请使用绑定账号扫码；如需更换请联系管理员解绑";
        }
        if (bindingRepository.findByYktName(yktName).isPresent()) {
            return "雨课堂账号「" + yktName + "」已绑定其他平台账号，一个雨课堂账号只能绑定一个平台账号";
        }
        RainBinding binding = new RainBinding();
        binding.setUserId(userId);
        binding.setYktName(yktName);
        binding.setAutoBound(true);
        bindingRepository.save(binding);
        log.info("平台用户 {} 已自动绑定雨课堂账号「{}」", userId, yktName);
        return null;
    }

    /** 免扫码续做（reuseYkt）：必须与绑定一致，防止借他人账号名直接续做。 */
    @Transactional(readOnly = true)
    public void checkReuseAllowed(Long userId, String reuseYkt) {
        if (!properties.bindRequired() || reuseYkt == null || reuseYkt.isBlank()) {
            return;
        }
        RainBinding mine = bindingRepository.findByUserId(userId).orElse(null);
        if (mine == null) {
            // 尚无绑定：无法校验，交给扫码登录后的 onScanLogin 首次绑定
            return;
        }
        if (!mine.getYktName().equals(reuseYkt)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "平台账号已绑定雨课堂账号「" + mine.getYktName() + "」，不能以「" + reuseYkt + "」续做");
        }
    }

    // ---------- 权益发放（订单核销 / 兑换码 / 管理员共用） ----------

    @Transactional
    public void grantDatePass(Long userId, String batch, String sourceOrderNo, Long grantedBy) {
        if (entitlementRepository.findFirstByUserIdAndKindAndTargetBatchAndRevokedFalseOrderByIdAsc(
                userId, VipEntitlement.KIND_DATE, batch).isPresent()) {
            return;
        }
        VipEntitlement e = new VipEntitlement();
        e.setUserId(userId);
        e.setKind(VipEntitlement.KIND_DATE);
        e.setTargetBatch(batch);
        e.setSourceOrderNo(sourceOrderNo);
        e.setGrantedBy(grantedBy);
        entitlementRepository.save(e);
    }

    /** 月卡：已有在期月卡则从其到期时间续期，否则从当前时间起算。 */
    @Transactional
    public Instant grantMonth(Long userId, int days, String sourceOrderNo, Long grantedBy) {
        Instant base = monthCardExpiresAt(userId);
        Instant now = Instant.now();
        if (base == null || base.isBefore(now)) {
            base = now;
        }
        Instant expiresAt = base.plusSeconds(days * 24L * 3600L);
        VipEntitlement e = new VipEntitlement();
        e.setUserId(userId);
        e.setKind(VipEntitlement.KIND_MONTH);
        e.setExpiresAt(expiresAt);
        e.setSourceOrderNo(sourceOrderNo);
        e.setGrantedBy(grantedBy);
        entitlementRepository.save(e);
        return expiresAt;
    }

    // ---------- 私有工具 ----------

    private VipOrder ownOrder(Long userId, String orderNo) {
        VipOrder order = orderRepository.findByOrderNo(orderNo)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "订单不存在"));
        if (!order.getUserId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权操作该订单");
        }
        return order;
    }

    /** 优惠码通用校验：存在由调用方保证；未禁用/未用尽/未过期/未达单用户上限。 */
    private void requireCouponUsable(VipCoupon coupon, Long userId, Instant now) {
        if (coupon.isDisabled()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "优惠码已被禁用");
        }
        if (coupon.exhausted()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "优惠码使用次数已达上限");
        }
        if (coupon.getExpiresAt() != null && now.isAfter(coupon.getExpiresAt())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "优惠码已过期");
        }
        if (couponUseRepository.countByCodeAndUserId(coupon.getCode(), userId) >= coupon.getPerUserLimit()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "您已使用过该优惠码");
        }
    }

    /** 直接兑换类码的核销：计数 + 流水（折扣码随订单核销，不走这里）。 */
    private void consumeCoupon(VipCoupon coupon, Long userId, String orderNo) {
        coupon.setUsedCount(coupon.usedCountOrDefault() + 1);
        couponRepository.save(coupon);
        VipCouponUse use = new VipCouponUse();
        use.setCode(coupon.getCode());
        use.setUserId(userId);
        use.setOrderNo(orderNo);
        couponUseRepository.save(use);
    }

    /** 窗口内适用于指定商品的最优活动；orderKind 传 "ALL" 时取任意适用活动。 */
    private VipActivityView effectiveActivity(Instant now, String orderKind) {
        return activityRepository.findByEnabledTrue().stream()
                .filter(a -> a.activeAt(now))
                .filter(a -> "ALL".equals(orderKind) || a.appliesToKind(orderKind))
                .reduce((best, a) -> a.getDiscountPercent() < best.getDiscountPercent() ? a : best)
                .map(a -> new VipActivityView(a.getId(), a.getName(), a.getDiscountPercent(),
                        a.getAppliesTo(), a.getStartsAt(), a.getEndsAt(), a.isEnabled()))
                .orElse(null);
    }

    private VipOrderView toOrderView(VipOrder order, String username, String activityName) {
        return new VipOrderView(
                order.getId(), order.getOrderNo(), order.getUserId(), username,
                order.getKind(), order.getTargetBatch(),
                order.getOriginalFen(), order.getDiscountFen(), order.getSuffixFen(), order.getPayableFen(),
                order.getStatus(), order.getPayChannel(), order.getCouponCode(),
                order.getRemark(), order.getAdminRemark(),
                order.getClaimedAt(), order.getPaidAt(), order.getExpiresAt(), order.getCreatedAt(),
                activityName);
    }

    /** 从通知文本提取金额（分）；识别不到返回 null。 */
    public static Integer extractAmountFen(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        Matcher m = AMOUNT_PATTERN.matcher(raw.replace(",", ""));
        if (!m.find()) {
            return null;
        }
        java.math.BigDecimal amount = new java.math.BigDecimal(m.group(1));
        return amount.movePointRight(2).intValue();
    }

    private static String randomToken(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}
